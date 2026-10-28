#pragma once

#include <winsock2.h>
#include <windows.h>
#include <wincrypt.h>

#include <optional>

#include "crypto.h"

class HostCertificate {
public:
    static std::optional<HostCertificate> LoadOrCreate(const IN_ADDR& address);

    HostCertificate(HostCertificate&& other) noexcept;
    HostCertificate(const HostCertificate&) = delete;
    HostCertificate& operator=(const HostCertificate&) = delete;
    HostCertificate& operator=(HostCertificate&&) = delete;
    ~HostCertificate();

    PCCERT_CONTEXT Context() const { return context; }
    const crypto::Digest& Fingerprint() const { return fingerprint; }

private:
    explicit HostCertificate(PCCERT_CONTEXT context);

    PCCERT_CONTEXT context;
    crypto::Digest fingerprint{};
};
