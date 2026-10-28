#pragma once

#include <optional>
#include <string>
#include <string_view>
#include <utility>
#include <variant>
#include <vector>

namespace json {

struct Value;
using Array = std::vector<Value>;
using Object = std::vector<std::pair<std::string, Value>>;

struct Value {
    std::variant<std::nullptr_t, bool, double, std::string, Array, Object> content;

    const std::string* AsString() const { return std::get_if<std::string>(&content); }
    const double* AsNumber() const { return std::get_if<double>(&content); }
    const Array* AsArray() const { return std::get_if<Array>(&content); }
    const Object* AsObject() const { return std::get_if<Object>(&content); }
    const Value* Find(std::string_view key) const;
    const std::string* FindString(std::string_view key) const;
};

std::optional<Value> Parse(std::string_view text);
std::string Quote(std::string_view text);

}
