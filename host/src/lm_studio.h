#pragma once

#include <windows.h>

#include <memory>
#include <optional>
#include <string>
#include <vector>

struct AvailableModel {
    std::string key;
    std::string displayName;
    int maxContextLength = 0;
};

struct LoadedModel {
    std::string instanceId;
    int contextLength = 0;
};

namespace lm_studio {

enum class ServerCheck { Ready, NotRunning, NotLmStudio, OpenToNetwork };

struct ModelList {
    ServerCheck server = ServerCheck::NotRunning;
    std::vector<AvailableModel> models;
};

class HttpExchange;

ServerCheck CheckServer();
ModelList ListModels();
std::optional<LoadedModel> Load(const std::string& modelKey, int contextLength);
bool Unload(const std::string& instanceId);

class ChatStream {
public:
    ChatStream();
    ChatStream(const ChatStream&) = delete;
    ChatStream& operator=(const ChatStream&) = delete;
    ~ChatStream();

    DWORD Open(const std::string& requestBody);
    std::optional<std::string> Read();

private:
    std::unique_ptr<HttpExchange> exchange;
};

}
