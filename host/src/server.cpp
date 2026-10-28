#include "server.h"

#include <ws2tcpip.h>
#include <iphlpapi.h>

#include <algorithm>
#include <charconv>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

#include "certificate.h"
#include "json.h"
#include "lm_studio.h"
#include "tailscale.h"
#include "tls_connection.h"

namespace {

constexpr u_short listenPort = 47123;
constexpr DWORD retryMilliseconds = 10 * 1000;
constexpr DWORD socketTimeoutMilliseconds = 30 * 1000;
constexpr DWORD requestDeadlineMilliseconds = 15 * 1000;
constexpr int maxConnections = 4;
constexpr SIZE_T workerStackBytes = 256 * 1024;
constexpr size_t maxHeaderBytes = 8 * 1024;
constexpr size_t maxBodyBytes = 256 * 1024;
constexpr size_t maxMessages = 200;
constexpr int maxReplyTokens = 16384;
constexpr int minContextLength = 2048;

std::atomic<int> activeConnections{0};

struct ListenerSession {
    AppState& state;
    HostCertificate certificate;
    TlsCredentials credentials;
};

struct ConnectionJob {
    SOCKET socket;
    std::shared_ptr<ListenerSession> session;
};

struct HttpRequest {
    std::string method;
    std::string path;
    std::string bearerToken;
    std::string body;
};

std::optional<IN_ADDR> FindTailscaleAddress() {
    ULONG size = 16 * 1024;
    std::vector<uint8_t> buffer(size);
    ULONG flags = GAA_FLAG_SKIP_ANYCAST | GAA_FLAG_SKIP_MULTICAST | GAA_FLAG_SKIP_DNS_SERVER;
    ULONG status = GetAdaptersAddresses(AF_INET, flags, nullptr, reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buffer.data()), &size);
    if (status == ERROR_BUFFER_OVERFLOW) {
        buffer.resize(size);
        status = GetAdaptersAddresses(AF_INET, flags, nullptr, reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buffer.data()), &size);
    }
    if (status != NO_ERROR) return std::nullopt;
    for (auto* adapter = reinterpret_cast<PIP_ADAPTER_ADDRESSES>(buffer.data()); adapter; adapter = adapter->Next) {
        if (adapter->OperStatus != IfOperStatusUp) continue;
        if (!adapter->Description || !std::wstring_view(adapter->Description).starts_with(L"Tailscale")) continue;
        for (auto* unicast = adapter->FirstUnicastAddress; unicast; unicast = unicast->Next) {
            const auto* ipv4 = reinterpret_cast<const sockaddr_in*>(unicast->Address.lpSockaddr);
            uint32_t hostOrder = ntohl(ipv4->sin_addr.s_addr);
            if ((hostOrder & 0xFFC00000u) == 0x64400000u) return ipv4->sin_addr;
        }
    }
    return std::nullopt;
}

SOCKET OpenListener(const IN_ADDR& address) {
    SOCKET socket = WSASocketW(AF_INET, SOCK_STREAM, IPPROTO_TCP, nullptr, 0, WSA_FLAG_OVERLAPPED | WSA_FLAG_NO_HANDLE_INHERIT);
    if (socket == INVALID_SOCKET) return INVALID_SOCKET;
    BOOL exclusive = TRUE;
    setsockopt(socket, SOL_SOCKET, SO_EXCLUSIVEADDRUSE, reinterpret_cast<const char*>(&exclusive), sizeof exclusive);
    sockaddr_in endpoint{};
    endpoint.sin_family = AF_INET;
    endpoint.sin_port = htons(listenPort);
    endpoint.sin_addr = address;
    if (bind(socket, reinterpret_cast<const sockaddr*>(&endpoint), sizeof endpoint) != 0 || listen(socket, 8) != 0) {
        closesocket(socket);
        return INVALID_SOCKET;
    }
    return socket;
}

bool EqualsIgnoreCase(std::string_view left, std::string_view right) {
    if (left.size() != right.size()) return false;
    for (size_t index = 0; index < left.size(); ++index) {
        if (tolower(static_cast<unsigned char>(left[index])) != tolower(static_cast<unsigned char>(right[index]))) return false;
    }
    return true;
}

std::string_view Trim(std::string_view text) {
    while (!text.empty() && (text.front() == ' ' || text.front() == '\t')) text.remove_prefix(1);
    while (!text.empty() && (text.back() == ' ' || text.back() == '\t')) text.remove_suffix(1);
    return text;
}

std::optional<HttpRequest> ReadRequest(TlsConnection& connection) {
    std::string buffer;
    size_t headerEnd = std::string::npos;
    while ((headerEnd = buffer.find("\r\n\r\n")) == std::string::npos) {
        if (buffer.size() > maxHeaderBytes) return std::nullopt;
        std::optional<std::string> chunk = connection.Read();
        if (!chunk) return std::nullopt;
        buffer += *chunk;
    }

    std::string_view head(buffer.data(), headerEnd);
    size_t requestLineEnd = head.find("\r\n");
    std::string_view requestLine = head.substr(0, requestLineEnd);
    size_t firstSpace = requestLine.find(' ');
    size_t secondSpace = firstSpace == std::string_view::npos ? std::string_view::npos : requestLine.find(' ', firstSpace + 1);
    if (secondSpace == std::string_view::npos) return std::nullopt;

    HttpRequest request;
    request.method = requestLine.substr(0, firstSpace);
    request.path = requestLine.substr(firstSpace + 1, secondSpace - firstSpace - 1);

    size_t contentLength = 0;
    size_t cursor = requestLineEnd == std::string_view::npos ? head.size() : requestLineEnd + 2;
    while (cursor < head.size()) {
        size_t lineEnd = head.find("\r\n", cursor);
        if (lineEnd == std::string_view::npos) lineEnd = head.size();
        std::string_view line = head.substr(cursor, lineEnd - cursor);
        cursor = lineEnd + 2;
        size_t colon = line.find(':');
        if (colon == std::string_view::npos) return std::nullopt;
        std::string_view name = Trim(line.substr(0, colon));
        std::string_view value = Trim(line.substr(colon + 1));
        if (EqualsIgnoreCase(name, "content-length")) {
            auto [stop, error] = std::from_chars(value.data(), value.data() + value.size(), contentLength);
            if (error != std::errc{} || stop != value.data() + value.size() || contentLength > maxBodyBytes) return std::nullopt;
        } else if (EqualsIgnoreCase(name, "authorization") && value.size() > 7 && EqualsIgnoreCase(value.substr(0, 7), "bearer ")) {
            request.bearerToken = Trim(value.substr(7));
        }
    }

    request.body = buffer.substr(headerEnd + 4);
    while (request.body.size() < contentLength) {
        std::optional<std::string> chunk = connection.Read();
        if (!chunk) return std::nullopt;
        request.body += *chunk;
    }
    if (request.body.size() > contentLength) return std::nullopt;
    return request;
}

std::string_view ReasonPhrase(int status) {
    switch (status) {
    case 200: return "OK";
    case 400: return "Bad Request";
    case 401: return "Unauthorized";
    case 403: return "Forbidden";
    case 404: return "Not Found";
    case 409: return "Conflict";
    case 503: return "Service Unavailable";
    default: return "Bad Gateway";
    }
}

void SendJson(TlsConnection& connection, int status, std::string_view body) {
    std::string response = "HTTP/1.1 " + std::to_string(status) + " " + std::string(ReasonPhrase(status)) + "\r\n";
    response += "Content-Type: application/json\r\n";
    response += "Content-Length: " + std::to_string(body.size()) + "\r\n";
    response += "Cache-Control: no-store\r\nConnection: close\r\n\r\n";
    response += body;
    connection.Write(response);
}

void SendError(TlsConnection& connection, int status, std::string_view code) {
    SendJson(connection, status, "{\"error\":" + json::Quote(code) + "}");
}

void HandlePair(TlsConnection& connection, ListenerSession& session, const HttpRequest& request) {
    std::optional<json::Value> document = json::Parse(request.body);
    const std::string* proofText = document ? document->FindString("proof") : nullptr;
    const std::string* deviceName = document ? document->FindString("device_name") : nullptr;
    std::optional<std::vector<uint8_t>> proof = proofText ? crypto::FromHex(*proofText) : std::nullopt;
    if (!proof || !deviceName) {
        SendError(connection, 400, "bad_request");
        return;
    }
    std::optional<PairingGrant> grant = session.state.CompletePairing(*proof, session.certificate.Fingerprint(), *deviceName);
    if (!grant) {
        SendError(connection, 403, "pairing_rejected");
        return;
    }
    SendJson(connection, 200, "{\"token\":" + json::Quote(grant->token) + ",\"server_proof\":" + json::Quote(grant->serverProof) + "}");
    SecureZeroMemory(grant->token.data(), grant->token.size());
}

void HandleStatus(TlsConnection& connection, ListenerSession& session) {
    ModelStatus model = session.state.Model();
    switch (model.state) {
    case ModelState::Loaded:
        SendJson(connection, 200, "{\"state\":\"ready\",\"model\":" + json::Quote(model.displayName) + ",\"context\":" + std::to_string(model.contextLength) + "}");
        return;
    case ModelState::Loading:
        SendJson(connection, 200, "{\"state\":\"loading\",\"model\":" + json::Quote(model.displayName) + "}");
        return;
    case ModelState::Unloading:
        SendJson(connection, 200, "{\"state\":\"unloading\"}");
        return;
    default:
        SendJson(connection, 200, "{\"state\":\"no_model\"}");
    }
}

std::string_view ServerCheckName(lm_studio::ServerCheck server) {
    switch (server) {
    case lm_studio::ServerCheck::Ready: return "ready";
    case lm_studio::ServerCheck::NotLmStudio: return "not_lm_studio";
    case lm_studio::ServerCheck::OpenToNetwork: return "open_to_network";
    default: return "not_running";
    }
}

void HandleModels(TlsConnection& connection) {
    lm_studio::ModelList available = lm_studio::ListModels();
    std::string body = "{\"server\":" + json::Quote(ServerCheckName(available.server)) + ",\"models\":[";
    for (size_t index = 0; index < available.models.size(); ++index) {
        const AvailableModel& model = available.models[index];
        if (index > 0) body += ',';
        body += "{\"key\":" + json::Quote(model.key) + ",\"name\":" + json::Quote(model.displayName) + ",\"max_context\":" + std::to_string(model.maxContextLength) + "}";
    }
    body += "]}";
    SendJson(connection, 200, body);
}

void HandleLoad(TlsConnection& connection, ListenerSession& session, const HttpRequest& request) {
    std::optional<json::Value> document = json::Parse(request.body);
    const std::string* key = document ? document->FindString("model") : nullptr;
    const json::Value* contextValue = document ? document->Find("context_length") : nullptr;
    const double* contextLength = contextValue ? contextValue->AsNumber() : nullptr;
    if (!key || !contextLength) {
        SendError(connection, 400, "bad_request");
        return;
    }
    lm_studio::ModelList available = lm_studio::ListModels();
    if (available.server != lm_studio::ServerCheck::Ready) {
        SendError(connection, 503, ServerCheckName(available.server));
        return;
    }
    auto match = std::find_if(available.models.begin(), available.models.end(), [key](const AvailableModel& model) { return model.key == *key; });
    if (match == available.models.end()) {
        SendError(connection, 404, "unknown_model");
        return;
    }
    if (*contextLength < minContextLength || *contextLength > std::numeric_limits<int>::max()) {
        SendError(connection, 400, "bad_context_length");
        return;
    }
    int requested = static_cast<int>(*contextLength);
    int ceiling = match->maxContextLength > 0 ? match->maxContextLength : requested;
    if (requested != *contextLength || requested < minContextLength || requested > ceiling) {
        SendError(connection, 400, "bad_context_length");
        return;
    }
    if (!session.state.LoadModel(*match, requested)) {
        SendError(connection, 409, "busy");
        return;
    }
    SendJson(connection, 200, "{\"state\":\"loading\"}");
}

void HandleUnload(TlsConnection& connection, ListenerSession& session) {
    if (!session.state.UnloadModel()) {
        SendError(connection, 409, "busy");
        return;
    }
    SendJson(connection, 200, "{\"state\":\"unloading\"}");
}

std::optional<std::string> BuildUpstreamBody(std::string_view body, std::string_view instanceId) {
    std::optional<json::Value> document = json::Parse(body);
    const json::Value* messages = document ? document->Find("messages") : nullptr;
    const json::Array* entries = messages ? messages->AsArray() : nullptr;
    if (!entries || entries->empty() || entries->size() > maxMessages) return std::nullopt;

    std::string upstream = "{\"model\":" + json::Quote(instanceId) + ",\"stream\":true,\"reasoning_effort\":\"none\"";
    const json::Value* temperatureValue = document->Find("temperature");
    if (temperatureValue) {
        const double* temperature = temperatureValue->AsNumber();
        if (!temperature || *temperature < 0 || *temperature > 2) return std::nullopt;
        upstream += ",\"temperature\":" + std::to_string(*temperature);
    }
    const json::Value* maxTokensValue = document->Find("max_tokens");
    if (maxTokensValue) {
        const double* maxTokens = maxTokensValue->AsNumber();
        if (!maxTokens || *maxTokens < 1 || *maxTokens > maxReplyTokens) return std::nullopt;
        upstream += ",\"max_tokens\":" + std::to_string(static_cast<int>(*maxTokens));
    }
    upstream += ",\"messages\":[";
    for (size_t index = 0; index < entries->size(); ++index) {
        const json::Value& entry = (*entries)[index];
        const std::string* role = entry.FindString("role");
        const std::string* content = entry.FindString("content");
        if (!role || !content) return std::nullopt;
        bool allowedRole = *role == "user" || *role == "assistant" || (*role == "system" && index == 0);
        if (!allowedRole) return std::nullopt;
        if (index > 0) upstream += ',';
        upstream += "{\"role\":" + json::Quote(*role) + ",\"content\":" + json::Quote(*content) + "}";
    }
    upstream += "]}";
    return upstream;
}

void HandleChat(TlsConnection& connection, ListenerSession& session, const HttpRequest& request) {
    ModelStatus model = session.state.Model();
    if (model.state != ModelState::Loaded) {
        SendError(connection, 409, "no_model");
        return;
    }
    std::optional<std::string> upstreamBody = BuildUpstreamBody(request.body, model.instanceId);
    if (!upstreamBody) {
        SendError(connection, 400, "bad_request");
        return;
    }
    lm_studio::ChatStream stream;
    DWORD upstreamStatus = stream.Open(*upstreamBody);
    if (upstreamStatus != 200) {
        SendError(connection, 502, upstreamStatus == 0 ? "lm_studio_unreachable" : "lm_studio_error");
        return;
    }
    if (!connection.Write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n")) return;
    while (std::optional<std::string> chunk = stream.Read()) {
        if (!connection.Write(*chunk)) return;
    }
}

VOID CALLBACK CutOffSlowRequest(PVOID socket, BOOLEAN) {
    shutdown(reinterpret_cast<SOCKET>(socket), SD_BOTH);
}

void HandleConnection(SOCKET socket, ListenerSession& session) {
    HANDLE deadline = nullptr;
    if (!CreateTimerQueueTimer(&deadline, nullptr, CutOffSlowRequest, reinterpret_cast<PVOID>(socket), requestDeadlineMilliseconds, 0, WT_EXECUTEONLYONCE)) return;
    TlsConnection connection(socket, session.credentials.Handle());
    bool accepted = connection.Accept();
    std::optional<HttpRequest> request = accepted ? ReadRequest(connection) : std::nullopt;
    DeleteTimerQueueTimer(nullptr, deadline, INVALID_HANDLE_VALUE);
    if (!accepted) return;
    if (!request) {
        SendError(connection, 400, "bad_request");
        connection.Close();
        return;
    }

    if (request->method == "POST" && request->path == "/pair") {
        HandlePair(connection, session, *request);
    } else if (!session.state.Authenticate(request->bearerToken)) {
        SendError(connection, 401, "unauthorized");
    } else if (request->method == "GET" && request->path == "/status") {
        HandleStatus(connection, session);
    } else if (request->method == "POST" && request->path == "/chat") {
        HandleChat(connection, session, *request);
    } else if (request->method == "GET" && request->path == "/models") {
        HandleModels(connection);
    } else if (request->method == "POST" && request->path == "/load") {
        HandleLoad(connection, session, *request);
    } else if (request->method == "POST" && request->path == "/unload") {
        HandleUnload(connection, session);
    } else {
        SendError(connection, 404, "not_found");
    }
    SecureZeroMemory(request->bearerToken.data(), request->bearerToken.size());
    connection.Close();
}

DWORD WINAPI ConnectionThread(LPVOID parameter) {
    std::unique_ptr<ConnectionJob> job(static_cast<ConnectionJob*>(parameter));
    HandleConnection(job->socket, *job->session);
    shutdown(job->socket, SD_BOTH);
    closesocket(job->socket);
    --activeConnections;
    return 0;
}

void AcceptConnections(SOCKET listenSocket, const std::shared_ptr<ListenerSession>& session) {
    for (;;) {
        SOCKET client = accept(listenSocket, nullptr, nullptr);
        if (client == INVALID_SOCKET) return;
        SetHandleInformation(reinterpret_cast<HANDLE>(client), HANDLE_FLAG_INHERIT, 0);
        if (activeConnections.load() >= maxConnections) {
            closesocket(client);
            continue;
        }
        DWORD timeout = socketTimeoutMilliseconds;
        setsockopt(client, SOL_SOCKET, SO_RCVTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);
        setsockopt(client, SOL_SOCKET, SO_SNDTIMEO, reinterpret_cast<const char*>(&timeout), sizeof timeout);

        ++activeConnections;
        auto job = std::make_unique<ConnectionJob>(ConnectionJob{client, session});
        HANDLE worker = CreateThread(nullptr, workerStackBytes, ConnectionThread, job.get(), STACK_SIZE_PARAM_IS_A_RESERVATION, nullptr);
        if (!worker) {
            closesocket(client);
            --activeConnections;
            continue;
        }
        job.release();
        CloseHandle(worker);
    }
}

}

Server::Server(AppState& state) : state(state), stopEvent(CreateEventW(nullptr, TRUE, FALSE, nullptr)) {}

Server::~Server() {
    Stop();
    CloseHandle(stopEvent);
}

void Server::Start() {
    thread = CreateThread(nullptr, 0, ThreadMain, this, 0, nullptr);
}

void Server::Stop() {
    SetEvent(stopEvent);
    SOCKET open = listener.exchange(INVALID_SOCKET);
    if (open != INVALID_SOCKET) closesocket(open);
    if (thread) {
        WaitForSingleObject(thread, 5000);
        CloseHandle(thread);
        thread = nullptr;
    }
}

DWORD WINAPI Server::ThreadMain(LPVOID parameter) {
    static_cast<Server*>(parameter)->Run();
    return 0;
}

namespace {

std::mutex boundAddressLock;
std::optional<std::wstring> boundAddress;

void SetBoundAddress(const IN_ADDR* address) {
    std::optional<std::wstring> text;
    wchar_t buffer[INET_ADDRSTRLEN]{};
    if (address && InetNtopW(AF_INET, address, buffer, INET_ADDRSTRLEN)) text = buffer;
    std::lock_guard lock(boundAddressLock);
    boundAddress = std::move(text);
}

}

std::optional<std::wstring> TailscaleAddressText() {
    std::lock_guard lock(boundAddressLock);
    return boundAddress;
}

void Server::Run() {
    while (WaitForSingleObject(stopEvent, 0) == WAIT_TIMEOUT) {
        std::optional<IN_ADDR> address = FindTailscaleAddress();
        std::optional<HostCertificate> certificate = address ? HostCertificate::LoadOrCreate(*address) : std::nullopt;
        std::optional<TlsCredentials> credentials = certificate ? TlsCredentials::Acquire(certificate->Context()) : std::nullopt;
        SOCKET listenSocket = credentials ? OpenListener(*address) : INVALID_SOCKET;
        if (listenSocket == INVALID_SOCKET) {
            WaitForSingleObject(stopEvent, retryMilliseconds);
            continue;
        }
        auto session = std::make_shared<ListenerSession>(ListenerSession{state, std::move(*certificate), std::move(*credentials)});
        listener = listenSocket;
        SetBoundAddress(&*address);
        if (WaitForSingleObject(stopEvent, 0) != WAIT_TIMEOUT) {
            SOCKET open = listener.exchange(INVALID_SOCKET);
            if (open != INVALID_SOCKET) closesocket(open);
            SetBoundAddress(nullptr);
            return;
        }
        AcceptConnections(listenSocket, session);
        SetBoundAddress(nullptr);
        SOCKET open = listener.exchange(INVALID_SOCKET);
        if (open != INVALID_SOCKET) closesocket(open);
    }
}
