#pragma once

#include <winsock2.h>
#include <windows.h>
#include <subauth.h>
#include <sspi.h>
#include <schannel.h>

#include <optional>
#include <string>
#include <string_view>
#include <vector>

class TlsCredentials {
public:
    static std::optional<TlsCredentials> Acquire(PCCERT_CONTEXT certificate);

    TlsCredentials(TlsCredentials&& other) noexcept;
    TlsCredentials(const TlsCredentials&) = delete;
    TlsCredentials& operator=(const TlsCredentials&) = delete;
    TlsCredentials& operator=(TlsCredentials&&) = delete;
    ~TlsCredentials();

    CredHandle* Handle() { return &handle; }

private:
    TlsCredentials() = default;

    CredHandle handle{};
    bool valid = false;
};

class TlsConnection {
public:
    TlsConnection(SOCKET socket, CredHandle* credentials);
    TlsConnection(const TlsConnection&) = delete;
    TlsConnection& operator=(const TlsConnection&) = delete;
    ~TlsConnection();

    bool Accept();
    std::optional<std::string> Read();
    bool Write(std::string_view plaintext);
    void Close();

private:
    SOCKET socket;
    CredHandle* credentials;
    CtxtHandle context{};
    bool hasContext = false;
    SecPkgContext_StreamSizes streamSizes{};
    std::vector<char> received;
    std::vector<char> packet;

    bool ReceiveMore();
    bool SendAll(const char* bytes, size_t size);
    bool Negotiate();
};
