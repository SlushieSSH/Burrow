#pragma once

#include <winsock2.h>
#include <windows.h>

#include <atomic>

#include "app_state.h"

class Server {
public:
    explicit Server(AppState& state);
    Server(const Server&) = delete;
    Server& operator=(const Server&) = delete;
    ~Server();

    void Start();
    void Stop();

private:
    AppState& state;
    HANDLE stopEvent;
    HANDLE thread = nullptr;
    std::atomic<SOCKET> listener{INVALID_SOCKET};

    static DWORD WINAPI ThreadMain(LPVOID parameter);
    void Run();
};
