#include "lm_studio.h"

#include <winsock2.h>
#include <iphlpapi.h>

#include <algorithm>
#include <cwctype>

#include "json.h"

#include <winhttp.h>

namespace lm_studio {

namespace {

constexpr INTERNET_PORT serverPort = 1234;
constexpr DWORD listTimeoutMilliseconds = 2000;
constexpr DWORD loadTimeoutMilliseconds = 15 * 60 * 1000;
constexpr DWORD unloadTimeoutMilliseconds = 60 * 1000;
constexpr DWORD chatTimeoutMilliseconds = 5 * 60 * 1000;

struct HttpReply {
    DWORD status = 0;
    std::string body;
};

}

class HttpExchange {
public:
    HttpExchange(const HttpExchange&) = delete;
    HttpExchange& operator=(const HttpExchange&) = delete;

    HttpExchange(const wchar_t* method, const wchar_t* path, const std::string& body, DWORD receiveTimeout) {
        session = WinHttpOpen(L"Burrow", WINHTTP_ACCESS_TYPE_NO_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
        if (!session) return;
        WinHttpSetTimeouts(session, 2000, 2000, 30000, static_cast<int>(receiveTimeout));
        connection = WinHttpConnect(session, L"127.0.0.1", serverPort, 0);
        if (!connection) return;
        request = WinHttpOpenRequest(connection, method, path, nullptr, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, 0);
        if (!request) return;
        DWORD bodySize = static_cast<DWORD>(body.size());
        const wchar_t* headers = body.empty() ? WINHTTP_NO_ADDITIONAL_HEADERS : L"Content-Type: application/json\r\n";
        DWORD headersLength = body.empty() ? 0 : static_cast<DWORD>(-1);
        if (!WinHttpSendRequest(request, headers, headersLength, const_cast<char*>(body.data()), bodySize, bodySize, 0)) return;
        if (!WinHttpReceiveResponse(request, nullptr)) return;
        DWORD statusSize = sizeof status;
        if (!WinHttpQueryHeaders(request, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &status, &statusSize, WINHTTP_NO_HEADER_INDEX)) status = 0;
    }

    ~HttpExchange() {
        if (request) WinHttpCloseHandle(request);
        if (connection) WinHttpCloseHandle(connection);
        if (session) WinHttpCloseHandle(session);
    }

    DWORD Status() const { return status; }

    std::optional<std::string> ReadChunk() {
        if (status == 0) return std::nullopt;
        char chunk[8192];
        DWORD count = 0;
        if (!WinHttpReadData(request, chunk, sizeof chunk, &count) || count == 0) return std::nullopt;
        return std::string(chunk, count);
    }

    HttpReply ReadAll() {
        HttpReply reply{status, {}};
        while (std::optional<std::string> chunk = ReadChunk()) reply.body += *chunk;
        return reply;
    }

private:
    HINTERNET session = nullptr;
    HINTERNET connection = nullptr;
    HINTERNET request = nullptr;
    DWORD status = 0;
};

namespace {

struct Listener {
    DWORD processId;
    bool everyInterface;
};

std::vector<Listener> Listeners() {
    ULONG size = 0;
    std::vector<uint8_t> buffer;
    DWORD outcome = ERROR_INSUFFICIENT_BUFFER;
    while (outcome == ERROR_INSUFFICIENT_BUFFER) {
        buffer.resize(size);
        outcome = GetExtendedTcpTable(buffer.empty() ? nullptr : buffer.data(), &size, FALSE, AF_INET, TCP_TABLE_OWNER_PID_LISTENER, 0);
    }
    if (outcome != NO_ERROR) return {};
    std::vector<Listener> owners;
    const auto* table = reinterpret_cast<const MIB_TCPTABLE_OWNER_PID*>(buffer.data());
    for (DWORD index = 0; index < table->dwNumEntries; ++index) {
        const MIB_TCPROW_OWNER_PID& row = table->table[index];
        bool onPort = ntohs(static_cast<u_short>(row.dwLocalPort)) == serverPort;
        bool local = row.dwLocalAddr == htonl(INADDR_LOOPBACK) || row.dwLocalAddr == INADDR_ANY;
        if (onPort && local) owners.push_back({row.dwOwningPid, row.dwLocalAddr == INADDR_ANY});
    }
    return owners;
}

std::wstring Lowercase(std::wstring text) {
    std::transform(text.begin(), text.end(), text.begin(), [](wchar_t letter) { return static_cast<wchar_t>(std::towlower(letter)); });
    return text;
}

bool IsLmStudioProcess(DWORD processId) {
    HANDLE process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, processId);
    if (!process) return false;
    wchar_t imagePath[MAX_PATH * 2];
    DWORD imagePathLength = static_cast<DWORD>(std::size(imagePath));
    BOOL queried = QueryFullProcessImageNameW(process, 0, imagePath, &imagePathLength);
    CloseHandle(process);
    if (!queried) return false;
    wchar_t installFolder[MAX_PATH];
    ExpandEnvironmentStringsW(L"%LOCALAPPDATA%\\Programs\\LM Studio\\", installFolder, MAX_PATH);
    return Lowercase(std::wstring(imagePath, imagePathLength)).starts_with(Lowercase(installFolder));
}

}

ServerCheck CheckServer() {
    std::vector<Listener> owners = Listeners();
    if (owners.empty()) return ServerCheck::NotRunning;
    if (!std::all_of(owners.begin(), owners.end(), [](const Listener& owner) { return IsLmStudioProcess(owner.processId); })) return ServerCheck::NotLmStudio;
    bool exposed = std::any_of(owners.begin(), owners.end(), [](const Listener& owner) { return owner.everyInterface; });
    return exposed ? ServerCheck::OpenToNetwork : ServerCheck::Ready;
}

namespace {

std::optional<json::Value> FetchModels() {
    HttpReply reply = HttpExchange(L"GET", L"/api/v1/models", {}, listTimeoutMilliseconds).ReadAll();
    if (reply.status != 200) return std::nullopt;
    return json::Parse(reply.body);
}

const json::Array* ModelEntries(const std::optional<json::Value>& document) {
    const json::Value* models = document ? document->Find("models") : nullptr;
    return models ? models->AsArray() : nullptr;
}

int ContextLengthOf(const std::string& instanceId) {
    std::optional<json::Value> document = FetchModels();
    const json::Array* entries = ModelEntries(document);
    if (!entries) return 0;
    for (const json::Value& entry : *entries) {
        const json::Value* instances = entry.Find("loaded_instances");
        const json::Array* loaded = instances ? instances->AsArray() : nullptr;
        if (!loaded) continue;
        for (const json::Value& instance : *loaded) {
            const std::string* id = instance.FindString("id");
            const json::Value* config = instance.Find("config");
            const json::Value* context = config ? config->Find("context_length") : nullptr;
            const double* length = context ? context->AsNumber() : nullptr;
            if (id && *id == instanceId && length) return static_cast<int>(*length);
        }
    }
    return 0;
}

}

ModelList ListModels() {
    ModelList list{CheckServer(), {}};
    if (list.server != ServerCheck::Ready) return list;
    std::optional<json::Value> document = FetchModels();
    const json::Array* entries = ModelEntries(document);
    if (!entries) return list;
    for (const json::Value& entry : *entries) {
        const std::string* type = entry.FindString("type");
        const std::string* key = entry.FindString("key");
        const std::string* displayName = entry.FindString("display_name");
        const json::Value* maxContext = entry.Find("max_context_length");
        const double* maxContextLength = maxContext ? maxContext->AsNumber() : nullptr;
        if (!type || *type != "llm" || !key || !displayName) continue;
        list.models.push_back({*key, *displayName, maxContextLength ? static_cast<int>(*maxContextLength) : 0});
    }
    return list;
}

std::optional<LoadedModel> Load(const std::string& modelKey, int contextLength) {
    if (CheckServer() != ServerCheck::Ready) return std::nullopt;
    std::string body = "{\"model\":" + json::Quote(modelKey);
    if (contextLength > 0) body += ",\"context_length\":" + std::to_string(contextLength);
    body += "}";
    HttpReply reply = HttpExchange(L"POST", L"/api/v1/models/load", body, loadTimeoutMilliseconds).ReadAll();
    if (reply.status != 200) return std::nullopt;
    std::optional<json::Value> document = json::Parse(reply.body);
    const std::string* instanceId = document ? document->FindString("instance_id") : nullptr;
    if (!instanceId) return std::nullopt;
    return LoadedModel{*instanceId, ContextLengthOf(*instanceId)};
}

bool Unload(const std::string& instanceId) {
    if (CheckServer() != ServerCheck::Ready) return false;
    HttpReply reply = HttpExchange(L"POST", L"/api/v1/models/unload", "{\"instance_id\":" + json::Quote(instanceId) + "}", unloadTimeoutMilliseconds).ReadAll();
    return reply.status == 200;
}

ChatStream::ChatStream() = default;

ChatStream::~ChatStream() = default;

DWORD ChatStream::Open(const std::string& requestBody) {
    if (CheckServer() != ServerCheck::Ready) return 0;
    exchange = std::make_unique<HttpExchange>(L"POST", L"/v1/chat/completions", requestBody, chatTimeoutMilliseconds);
    return exchange->Status();
}

std::optional<std::string> ChatStream::Read() {
    return exchange->ReadChunk();
}

}
