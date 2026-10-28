#include "certificate.h"

#include <ncrypt.h>

#include <cstring>
#include <string>
#include <vector>

namespace {

// Phones pin the certificate with this name at pairing; a new one under this name means every phone pairs again.
constexpr wchar_t certificateCommonName[] = L"Burrow Host";
// The TLS private key is stored in the Windows key store under this name; renaming it orphans the key and the server stops serving TLS.
constexpr wchar_t keyName[] = L"BurrowHostKey";

bool CoversAddress(PCCERT_CONTEXT certificate, const IN_ADDR& address) {
    PCERT_EXTENSION extension = CertFindExtension(szOID_SUBJECT_ALT_NAME2, certificate->pCertInfo->cExtension, certificate->pCertInfo->rgExtension);
    if (!extension) return false;
    CERT_ALT_NAME_INFO* names = nullptr;
    DWORD size = 0;
    if (!CryptDecodeObjectEx(X509_ASN_ENCODING, X509_ALTERNATE_NAME, extension->Value.pbData, extension->Value.cbData, CRYPT_DECODE_ALLOC_FLAG, nullptr, &names, &size)) {
        return false;
    }
    bool covered = false;
    for (DWORD index = 0; index < names->cAltEntry; ++index) {
        const CERT_ALT_NAME_ENTRY& entry = names->rgAltEntry[index];
        if (entry.dwAltNameChoice != CERT_ALT_NAME_IP_ADDRESS || entry.IPAddress.cbData != sizeof(IN_ADDR)) continue;
        if (std::memcmp(entry.IPAddress.pbData, &address, sizeof(IN_ADDR)) == 0) covered = true;
    }
    LocalFree(names);
    return covered;
}

bool IsCurrent(PCCERT_CONTEXT certificate, const IN_ADDR& address) {
    FILETIME now{};
    GetSystemTimeAsFileTime(&now);
    if (CompareFileTime(&now, &certificate->pCertInfo->NotAfter) >= 0) return false;
    return CoversAddress(certificate, address);
}

PCCERT_CONTEXT FindUsable(HCERTSTORE store, const IN_ADDR& address) {
    PCCERT_CONTEXT usable = nullptr;
    std::vector<PCCERT_CONTEXT> stale;
    PCCERT_CONTEXT candidate = nullptr;
    while ((candidate = CertFindCertificateInStore(store, X509_ASN_ENCODING, 0, CERT_FIND_SUBJECT_STR_W, certificateCommonName, candidate))) {
        if (!usable && IsCurrent(candidate, address)) {
            usable = CertDuplicateCertificateContext(candidate);
        } else {
            stale.push_back(CertDuplicateCertificateContext(candidate));
        }
    }
    for (PCCERT_CONTEXT old : stale) {
        CertDeleteCertificateFromStore(old);
    }
    return usable;
}

NCRYPT_KEY_HANDLE OpenOrCreateKey() {
    NCRYPT_PROV_HANDLE provider = 0;
    if (NCryptOpenStorageProvider(&provider, MS_KEY_STORAGE_PROVIDER, 0) != ERROR_SUCCESS) return 0;
    NCRYPT_KEY_HANDLE key = 0;
    if (NCryptOpenKey(provider, &key, keyName, 0, 0) != ERROR_SUCCESS) {
        key = 0;
        if (NCryptCreatePersistedKey(provider, &key, NCRYPT_ECDSA_P256_ALGORITHM, keyName, 0, 0) != ERROR_SUCCESS || NCryptFinalizeKey(key, 0) != ERROR_SUCCESS) {
            if (key) NCryptFreeObject(key);
            key = 0;
        }
    }
    NCryptFreeObject(provider);
    return key;
}

PCCERT_CONTEXT CreateSelfSigned(HCERTSTORE store, const IN_ADDR& address) {
    NCRYPT_KEY_HANDLE key = OpenOrCreateKey();
    if (!key) return nullptr;

    std::wstring subject = std::wstring(L"CN=") + certificateCommonName;
    DWORD subjectSize = 0;
    CertStrToNameW(X509_ASN_ENCODING, subject.c_str(), CERT_X500_NAME_STR, nullptr, nullptr, &subjectSize, nullptr);
    std::vector<BYTE> subjectBytes(subjectSize);
    CertStrToNameW(X509_ASN_ENCODING, subject.c_str(), CERT_X500_NAME_STR, nullptr, subjectBytes.data(), &subjectSize, nullptr);
    CERT_NAME_BLOB subjectBlob{subjectSize, subjectBytes.data()};

    IN_ADDR addressCopy = address;
    CERT_ALT_NAME_ENTRY addressEntry{};
    addressEntry.dwAltNameChoice = CERT_ALT_NAME_IP_ADDRESS;
    addressEntry.IPAddress = {sizeof(IN_ADDR), reinterpret_cast<BYTE*>(&addressCopy)};
    CERT_ALT_NAME_INFO alternateNames{1, &addressEntry};
    BYTE* encodedNames = nullptr;
    DWORD encodedNamesSize = 0;
    if (!CryptEncodeObjectEx(X509_ASN_ENCODING, X509_ALTERNATE_NAME, &alternateNames, CRYPT_ENCODE_ALLOC_FLAG, nullptr, &encodedNames, &encodedNamesSize)) {
        NCryptFreeObject(key);
        return nullptr;
    }
    CERT_EXTENSION extension{const_cast<LPSTR>(szOID_SUBJECT_ALT_NAME2), FALSE, {encodedNamesSize, encodedNames}};
    CERT_EXTENSIONS extensions{1, &extension};

    CRYPT_KEY_PROV_INFO keyProvider{};
    keyProvider.pwszContainerName = const_cast<LPWSTR>(keyName);
    keyProvider.pwszProvName = const_cast<LPWSTR>(MS_KEY_STORAGE_PROVIDER);

    CRYPT_ALGORITHM_IDENTIFIER signature{const_cast<LPSTR>(szOID_ECDSA_SHA256), {}};

    SYSTEMTIME start{};
    GetSystemTime(&start);
    SYSTEMTIME end = start;
    end.wYear = static_cast<WORD>(end.wYear + 20);
    if (end.wMonth == 2 && end.wDay == 29) end.wDay = 28;

    PCCERT_CONTEXT created = CertCreateSelfSignCertificate(key, &subjectBlob, 0, &keyProvider, &signature, &start, &end, &extensions);
    LocalFree(encodedNames);
    NCryptFreeObject(key);
    if (!created) return nullptr;

    PCCERT_CONTEXT stored = nullptr;
    BOOL added = CertAddCertificateContextToStore(store, created, CERT_STORE_ADD_REPLACE_EXISTING, &stored);
    CertFreeCertificateContext(created);
    return added ? stored : nullptr;
}

}

std::optional<HostCertificate> HostCertificate::LoadOrCreate(const IN_ADDR& address) {
    HCERTSTORE store = CertOpenStore(CERT_STORE_PROV_SYSTEM_W, 0, 0, CERT_SYSTEM_STORE_CURRENT_USER, L"My");
    if (!store) return std::nullopt;
    PCCERT_CONTEXT certificate = FindUsable(store, address);
    if (!certificate) certificate = CreateSelfSigned(store, address);
    CertCloseStore(store, 0);
    if (!certificate) return std::nullopt;
    return HostCertificate(certificate);
}

HostCertificate::HostCertificate(PCCERT_CONTEXT context) : context(context) {
    fingerprint = crypto::Sha256({context->pbCertEncoded, context->cbCertEncoded});
}

HostCertificate::HostCertificate(HostCertificate&& other) noexcept : context(other.context), fingerprint(other.fingerprint) {
    other.context = nullptr;
}

HostCertificate::~HostCertificate() {
    if (context) CertFreeCertificateContext(context);
}
