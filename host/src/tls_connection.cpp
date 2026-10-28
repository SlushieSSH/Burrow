#include "tls_connection.h"

#include <algorithm>
#include <cstring>

namespace {

constexpr ULONG acceptFlags = ASC_REQ_ALLOCATE_MEMORY | ASC_REQ_STREAM | ASC_REQ_CONFIDENTIALITY | ASC_REQ_REPLAY_DETECT | ASC_REQ_SEQUENCE_DETECT;
constexpr size_t maxBufferedBytes = 1 << 17;

}

std::optional<TlsCredentials> TlsCredentials::Acquire(PCCERT_CONTEXT certificate) {
    PCCERT_CONTEXT certificates[] = {certificate};
    TLS_PARAMETERS tlsParameters{};
    tlsParameters.grbitDisabledProtocols = static_cast<DWORD>(~(SP_PROT_TLS1_2_SERVER | SP_PROT_TLS1_3_SERVER));
    SCH_CREDENTIALS schannelCredentials{};
    schannelCredentials.dwVersion = SCH_CREDENTIALS_VERSION;
    schannelCredentials.cCreds = 1;
    schannelCredentials.paCred = certificates;
    schannelCredentials.dwFlags = SCH_USE_STRONG_CRYPTO;
    schannelCredentials.cTlsParameters = 1;
    schannelCredentials.pTlsParameters = &tlsParameters;

    TlsCredentials credentials;
    TimeStamp expiry{};
    SECURITY_STATUS status = AcquireCredentialsHandleW(nullptr, const_cast<LPWSTR>(UNISP_NAME_W), SECPKG_CRED_INBOUND, nullptr, &schannelCredentials, nullptr, nullptr, &credentials.handle, &expiry);
    if (status != SEC_E_OK) return std::nullopt;
    credentials.valid = true;
    return credentials;
}

TlsCredentials::TlsCredentials(TlsCredentials&& other) noexcept : handle(other.handle), valid(other.valid) {
    other.valid = false;
}

TlsCredentials::~TlsCredentials() {
    if (valid) FreeCredentialsHandle(&handle);
}

TlsConnection::TlsConnection(SOCKET socket, CredHandle* credentials) : socket(socket), credentials(credentials) {}

TlsConnection::~TlsConnection() {
    if (hasContext) DeleteSecurityContext(&context);
}

bool TlsConnection::ReceiveMore() {
    if (received.size() >= maxBufferedBytes) return false;
    char chunk[16384];
    int count = recv(socket, chunk, sizeof chunk, 0);
    if (count <= 0) return false;
    received.insert(received.end(), chunk, chunk + count);
    return true;
}

bool TlsConnection::SendAll(const char* bytes, size_t size) {
    while (size > 0) {
        int sent = send(socket, bytes, static_cast<int>(std::min<size_t>(size, 1 << 20)), 0);
        if (sent <= 0) return false;
        bytes += sent;
        size -= static_cast<size_t>(sent);
    }
    return true;
}

bool TlsConnection::Negotiate() {
    for (;;) {
        if (received.empty() && !ReceiveMore()) return false;

        SecBuffer inputBuffers[2]{};
        inputBuffers[0] = {static_cast<unsigned long>(received.size()), SECBUFFER_TOKEN, received.data()};
        inputBuffers[1] = {0, SECBUFFER_EMPTY, nullptr};
        SecBufferDesc input{SECBUFFER_VERSION, 2, inputBuffers};

        SecBuffer outputBuffer{0, SECBUFFER_TOKEN, nullptr};
        SecBufferDesc output{SECBUFFER_VERSION, 1, &outputBuffer};

        ULONG attributes = 0;
        SECURITY_STATUS status = AcceptSecurityContext(credentials, hasContext ? &context : nullptr, &input, acceptFlags, 0, &context, &output, &attributes, nullptr);
        if (status == SEC_E_OK || status == SEC_I_CONTINUE_NEEDED) hasContext = true;

        if (outputBuffer.pvBuffer) {
            bool sent = SendAll(static_cast<const char*>(outputBuffer.pvBuffer), outputBuffer.cbBuffer);
            FreeContextBuffer(outputBuffer.pvBuffer);
            if (!sent) return false;
        }

        if (status == SEC_E_INCOMPLETE_MESSAGE) {
            if (!ReceiveMore()) return false;
            continue;
        }
        if (status != SEC_E_OK && status != SEC_I_CONTINUE_NEEDED) return false;

        if (inputBuffers[1].BufferType == SECBUFFER_EXTRA) {
            std::vector<char> extra(received.end() - inputBuffers[1].cbBuffer, received.end());
            received = std::move(extra);
        } else {
            received.clear();
        }
        if (status == SEC_E_OK) return true;
    }
}

bool TlsConnection::Accept() {
    if (!Negotiate()) return false;
    if (QueryContextAttributesW(&context, SECPKG_ATTR_STREAM_SIZES, &streamSizes) != SEC_E_OK) return false;
    packet.resize(streamSizes.cbHeader + streamSizes.cbMaximumMessage + streamSizes.cbTrailer);
    return true;
}

std::optional<std::string> TlsConnection::Read() {
    for (;;) {
        if (received.empty()) {
            if (!ReceiveMore()) return std::nullopt;
            continue;
        }

        SecBuffer buffers[4]{};
        buffers[0] = {static_cast<unsigned long>(received.size()), SECBUFFER_DATA, received.data()};
        buffers[1] = {0, SECBUFFER_EMPTY, nullptr};
        buffers[2] = {0, SECBUFFER_EMPTY, nullptr};
        buffers[3] = {0, SECBUFFER_EMPTY, nullptr};
        SecBufferDesc message{SECBUFFER_VERSION, 4, buffers};
        SECURITY_STATUS status = DecryptMessage(&context, &message, 0, nullptr);

        if (status == SEC_E_INCOMPLETE_MESSAGE) {
            if (!ReceiveMore()) return std::nullopt;
            continue;
        }
        if (status != SEC_E_OK && status != SEC_I_RENEGOTIATE) return std::nullopt;

        std::string plaintext;
        std::vector<char> extra;
        for (const SecBuffer& buffer : buffers) {
            const char* start = static_cast<const char*>(buffer.pvBuffer);
            if (buffer.BufferType == SECBUFFER_DATA) plaintext.append(start, buffer.cbBuffer);
            if (buffer.BufferType == SECBUFFER_EXTRA) extra.assign(start, start + buffer.cbBuffer);
        }
        received = std::move(extra);

        if (status == SEC_I_RENEGOTIATE && !Negotiate()) return std::nullopt;
        if (!plaintext.empty()) return plaintext;
    }
}

bool TlsConnection::Write(std::string_view plaintext) {
    while (!plaintext.empty()) {
        size_t chunkSize = std::min<size_t>(plaintext.size(), streamSizes.cbMaximumMessage);
        char* header = packet.data();
        char* body = header + streamSizes.cbHeader;
        char* trailer = body + chunkSize;
        std::memcpy(body, plaintext.data(), chunkSize);

        SecBuffer buffers[4]{};
        buffers[0] = {streamSizes.cbHeader, SECBUFFER_STREAM_HEADER, header};
        buffers[1] = {static_cast<unsigned long>(chunkSize), SECBUFFER_DATA, body};
        buffers[2] = {streamSizes.cbTrailer, SECBUFFER_STREAM_TRAILER, trailer};
        buffers[3] = {0, SECBUFFER_EMPTY, nullptr};
        SecBufferDesc message{SECBUFFER_VERSION, 4, buffers};
        if (EncryptMessage(&context, 0, &message, 0) != SEC_E_OK) return false;

        size_t total = static_cast<size_t>(buffers[0].cbBuffer) + buffers[1].cbBuffer + buffers[2].cbBuffer;
        if (!SendAll(header, total)) return false;
        plaintext.remove_prefix(chunkSize);
    }
    return true;
}

void TlsConnection::Close() {
    if (!hasContext) return;
    DWORD shutdownToken = SCHANNEL_SHUTDOWN;
    SecBuffer tokenBuffer{sizeof shutdownToken, SECBUFFER_TOKEN, &shutdownToken};
    SecBufferDesc tokenDescription{SECBUFFER_VERSION, 1, &tokenBuffer};
    if (ApplyControlToken(&context, &tokenDescription) != SEC_E_OK) return;

    SecBuffer outputBuffer{0, SECBUFFER_TOKEN, nullptr};
    SecBufferDesc output{SECBUFFER_VERSION, 1, &outputBuffer};
    ULONG attributes = 0;
    AcceptSecurityContext(credentials, &context, nullptr, acceptFlags, 0, nullptr, &output, &attributes, nullptr);
    if (outputBuffer.pvBuffer) {
        SendAll(static_cast<const char*>(outputBuffer.pvBuffer), outputBuffer.cbBuffer);
        FreeContextBuffer(outputBuffer.pvBuffer);
    }
}
