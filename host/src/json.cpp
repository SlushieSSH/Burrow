#include "json.h"

#include <charconv>
#include <cmath>
#include <cstdint>
#include <cstdio>

namespace json {

const Value* Value::Find(std::string_view key) const {
    const Object* object = AsObject();
    if (!object) return nullptr;
    for (const auto& [name, value] : *object) {
        if (name == key) return &value;
    }
    return nullptr;
}

const std::string* Value::FindString(std::string_view key) const {
    const Value* value = Find(key);
    return value ? value->AsString() : nullptr;
}

namespace {

constexpr int maxDepth = 32;

void AppendUtf8(std::string& output, uint32_t codePoint) {
    if (codePoint < 0x80) {
        output += static_cast<char>(codePoint);
        return;
    }
    if (codePoint < 0x800) {
        output += static_cast<char>(0xC0 | (codePoint >> 6));
        output += static_cast<char>(0x80 | (codePoint & 0x3F));
        return;
    }
    if (codePoint < 0x10000) {
        output += static_cast<char>(0xE0 | (codePoint >> 12));
        output += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
        output += static_cast<char>(0x80 | (codePoint & 0x3F));
        return;
    }
    output += static_cast<char>(0xF0 | (codePoint >> 18));
    output += static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F));
    output += static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F));
    output += static_cast<char>(0x80 | (codePoint & 0x3F));
}

class Parser {
public:
    explicit Parser(std::string_view text) : text(text) {}

    std::optional<Value> ParseDocument() {
        std::optional<Value> value = ParseValue(0);
        SkipWhitespace();
        if (!value || position != text.size()) return std::nullopt;
        return value;
    }

private:
    std::string_view text;
    size_t position = 0;

    void SkipWhitespace() {
        while (position < text.size()) {
            char current = text[position];
            if (current != ' ' && current != '\t' && current != '\n' && current != '\r') return;
            ++position;
        }
    }

    bool ConsumeLiteral(std::string_view literal) {
        if (text.substr(position, literal.size()) != literal) return false;
        position += literal.size();
        return true;
    }

    bool ConsumeChar(char expected) {
        SkipWhitespace();
        if (position >= text.size() || text[position] != expected) return false;
        ++position;
        return true;
    }

    std::optional<Value> ParseValue(int depth) {
        if (depth > maxDepth) return std::nullopt;
        SkipWhitespace();
        if (position >= text.size()) return std::nullopt;
        char current = text[position];
        if (current == '{') return ParseObject(depth);
        if (current == '[') return ParseArray(depth);
        if (current == '"') {
            std::optional<std::string> parsed = ParseString();
            if (!parsed) return std::nullopt;
            return Value{std::move(*parsed)};
        }
        if (ConsumeLiteral("true")) return Value{true};
        if (ConsumeLiteral("false")) return Value{false};
        if (ConsumeLiteral("null")) return Value{nullptr};
        return ParseNumber();
    }

    std::optional<Value> ParseNumber() {
        double number = 0;
        const char* begin = text.data() + position;
        const char* end = text.data() + text.size();
        const char* digits = begin != end && *begin == '-' ? begin + 1 : begin;
        if (digits == end || *digits < '0' || *digits > '9') return std::nullopt;
        auto [stop, error] = std::from_chars(begin, end, number);
        if (error != std::errc{} || stop == begin || !std::isfinite(number)) return std::nullopt;
        position += static_cast<size_t>(stop - begin);
        return Value{number};
    }

    std::optional<uint32_t> ParseHex4() {
        if (position + 4 > text.size()) return std::nullopt;
        uint32_t codeUnit = 0;
        const char* begin = text.data() + position;
        auto [stop, error] = std::from_chars(begin, begin + 4, codeUnit, 16);
        if (error != std::errc{} || stop != begin + 4) return std::nullopt;
        position += 4;
        return codeUnit;
    }

    std::optional<std::string> ParseString() {
        ++position;
        std::string output;
        while (position < text.size()) {
            char current = text[position++];
            if (current == '"') return output;
            if (static_cast<unsigned char>(current) < 0x20) return std::nullopt;
            if (current != '\\') {
                output += current;
                continue;
            }
            if (position >= text.size()) return std::nullopt;
            char escape = text[position++];
            switch (escape) {
            case '"': output += '"'; break;
            case '\\': output += '\\'; break;
            case '/': output += '/'; break;
            case 'b': output += '\b'; break;
            case 'f': output += '\f'; break;
            case 'n': output += '\n'; break;
            case 'r': output += '\r'; break;
            case 't': output += '\t'; break;
            case 'u': {
                std::optional<uint32_t> leading = ParseHex4();
                if (!leading) return std::nullopt;
                uint32_t codePoint = *leading;
                if (codePoint >= 0xD800 && codePoint <= 0xDBFF) {
                    if (!ConsumeLiteral("\\u")) return std::nullopt;
                    std::optional<uint32_t> trailing = ParseHex4();
                    if (!trailing || *trailing < 0xDC00 || *trailing > 0xDFFF) return std::nullopt;
                    codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (*trailing - 0xDC00);
                } else if (codePoint >= 0xDC00 && codePoint <= 0xDFFF) {
                    return std::nullopt;
                }
                AppendUtf8(output, codePoint);
                break;
            }
            default:
                return std::nullopt;
            }
        }
        return std::nullopt;
    }

    std::optional<Value> ParseArray(int depth) {
        ++position;
        Array items;
        if (ConsumeChar(']')) return Value{std::move(items)};
        for (;;) {
            std::optional<Value> item = ParseValue(depth + 1);
            if (!item) return std::nullopt;
            items.push_back(std::move(*item));
            if (ConsumeChar(']')) return Value{std::move(items)};
            if (!ConsumeChar(',')) return std::nullopt;
        }
    }

    std::optional<Value> ParseObject(int depth) {
        ++position;
        Object members;
        if (ConsumeChar('}')) return Value{std::move(members)};
        for (;;) {
            SkipWhitespace();
            if (position >= text.size() || text[position] != '"') return std::nullopt;
            std::optional<std::string> key = ParseString();
            if (!key || !ConsumeChar(':')) return std::nullopt;
            std::optional<Value> member = ParseValue(depth + 1);
            if (!member) return std::nullopt;
            members.emplace_back(std::move(*key), std::move(*member));
            if (ConsumeChar('}')) return Value{std::move(members)};
            if (!ConsumeChar(',')) return std::nullopt;
        }
    }
};

}

std::optional<Value> Parse(std::string_view text) {
    return Parser(text).ParseDocument();
}

std::string Quote(std::string_view text) {
    std::string output = "\"";
    for (char current : text) {
        switch (current) {
        case '"': output += "\\\""; break;
        case '\\': output += "\\\\"; break;
        case '\n': output += "\\n"; break;
        case '\r': output += "\\r"; break;
        case '\t': output += "\\t"; break;
        default:
            if (static_cast<unsigned char>(current) < 0x20) {
                char escaped[8];
                std::snprintf(escaped, sizeof escaped, "\\u%04x", static_cast<unsigned>(current));
                output += escaped;
            } else {
                output += current;
            }
        }
    }
    output += '"';
    return output;
}

}
