#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

namespace crypto {

using Digest = std::array<uint8_t, 32>;

std::vector<uint8_t> RandomBytes(size_t count);
Digest Sha256(std::span<const uint8_t> input);
Digest HmacSha256(std::span<const uint8_t> key, std::span<const uint8_t> message);
bool EqualConstantTime(std::span<const uint8_t> left, std::span<const uint8_t> right);
std::string Base64Url(std::span<const uint8_t> bytes);
std::string Hex(std::span<const uint8_t> bytes);
std::optional<std::vector<uint8_t>> FromHex(std::string_view text);
std::vector<uint8_t> Protect(std::span<const uint8_t> plaintext);
std::optional<std::vector<uint8_t>> Unprotect(std::span<const uint8_t> sealed);

inline std::span<const uint8_t> AsBytes(std::string_view text) {
    return {reinterpret_cast<const uint8_t*>(text.data()), text.size()};
}

}
