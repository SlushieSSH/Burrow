#include "crypto.h"

#include <windows.h>
#include <bcrypt.h>
#include <dpapi.h>
#include <wincrypt.h>

#include <stdexcept>

namespace crypto {

namespace {

// Mixed into every DPAPI seal of the device list; changing it makes the saved devices file unreadable.
constexpr std::string_view protectionEntropy = "Burrow devices v1";

PUCHAR Mutable(std::span<const uint8_t> bytes) {
    return const_cast<PUCHAR>(bytes.data());
}

}

std::vector<uint8_t> RandomBytes(size_t count) {
    std::vector<uint8_t> bytes(count);
    NTSTATUS status = BCryptGenRandom(nullptr, bytes.data(), static_cast<ULONG>(bytes.size()), BCRYPT_USE_SYSTEM_PREFERRED_RNG);
    if (!BCRYPT_SUCCESS(status)) throw std::runtime_error("BCryptGenRandom failed");
    return bytes;
}

Digest Sha256(std::span<const uint8_t> input) {
    Digest digest{};
    NTSTATUS status = BCryptHash(BCRYPT_SHA256_ALG_HANDLE, nullptr, 0, Mutable(input), static_cast<ULONG>(input.size()), digest.data(), static_cast<ULONG>(digest.size()));
    if (!BCRYPT_SUCCESS(status)) throw std::runtime_error("SHA-256 failed");
    return digest;
}

Digest HmacSha256(std::span<const uint8_t> key, std::span<const uint8_t> message) {
    Digest digest{};
    NTSTATUS status = BCryptHash(BCRYPT_HMAC_SHA256_ALG_HANDLE, Mutable(key), static_cast<ULONG>(key.size()), Mutable(message), static_cast<ULONG>(message.size()), digest.data(), static_cast<ULONG>(digest.size()));
    if (!BCRYPT_SUCCESS(status)) throw std::runtime_error("HMAC-SHA256 failed");
    return digest;
}

bool EqualConstantTime(std::span<const uint8_t> left, std::span<const uint8_t> right) {
    if (left.size() != right.size()) return false;
    uint8_t difference = 0;
    for (size_t index = 0; index < left.size(); ++index) {
        difference |= static_cast<uint8_t>(left[index] ^ right[index]);
    }
    return difference == 0;
}

std::string Base64Url(std::span<const uint8_t> bytes) {
    DWORD length = 0;
    DWORD flags = CRYPT_STRING_BASE64 | CRYPT_STRING_NOCRLF;
    CryptBinaryToStringA(bytes.data(), static_cast<DWORD>(bytes.size()), flags, nullptr, &length);
    std::string encoded(length, '\0');
    CryptBinaryToStringA(bytes.data(), static_cast<DWORD>(bytes.size()), flags, encoded.data(), &length);
    encoded.resize(length);
    std::string urlSafe;
    for (char current : encoded) {
        if (current == '=') continue;
        urlSafe += current == '+' ? '-' : current == '/' ? '_' : current;
    }
    return urlSafe;
}

std::string Hex(std::span<const uint8_t> bytes) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string text;
    text.reserve(bytes.size() * 2);
    for (uint8_t byte : bytes) {
        text += digits[byte >> 4];
        text += digits[byte & 0x0F];
    }
    return text;
}

std::optional<std::vector<uint8_t>> FromHex(std::string_view text) {
    if (text.size() % 2 != 0) return std::nullopt;
    auto nibble = [](char digit) -> int {
        if (digit >= '0' && digit <= '9') return digit - '0';
        if (digit >= 'a' && digit <= 'f') return digit - 'a' + 10;
        if (digit >= 'A' && digit <= 'F') return digit - 'A' + 10;
        return -1;
    };
    std::vector<uint8_t> bytes;
    bytes.reserve(text.size() / 2);
    for (size_t index = 0; index < text.size(); index += 2) {
        int high = nibble(text[index]);
        int low = nibble(text[index + 1]);
        if (high < 0 || low < 0) return std::nullopt;
        bytes.push_back(static_cast<uint8_t>((high << 4) | low));
    }
    return bytes;
}

std::vector<uint8_t> Protect(std::span<const uint8_t> plaintext) {
    DATA_BLOB input{static_cast<DWORD>(plaintext.size()), Mutable(plaintext)};
    DATA_BLOB entropy{static_cast<DWORD>(protectionEntropy.size()), Mutable(AsBytes(protectionEntropy))};
    DATA_BLOB output{};
    if (!CryptProtectData(&input, nullptr, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output)) {
        throw std::runtime_error("CryptProtectData failed");
    }
    std::vector<uint8_t> sealed(output.pbData, output.pbData + output.cbData);
    LocalFree(output.pbData);
    return sealed;
}

std::optional<std::vector<uint8_t>> Unprotect(std::span<const uint8_t> sealed) {
    DATA_BLOB input{static_cast<DWORD>(sealed.size()), Mutable(sealed)};
    DATA_BLOB entropy{static_cast<DWORD>(protectionEntropy.size()), Mutable(AsBytes(protectionEntropy))};
    DATA_BLOB output{};
    if (!CryptUnprotectData(&input, nullptr, &entropy, nullptr, nullptr, CRYPTPROTECT_UI_FORBIDDEN, &output)) {
        return std::nullopt;
    }
    std::vector<uint8_t> plaintext(output.pbData, output.pbData + output.cbData);
    SecureZeroMemory(output.pbData, output.cbData);
    LocalFree(output.pbData);
    return plaintext;
}

}
