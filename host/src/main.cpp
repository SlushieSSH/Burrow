#include <winsock2.h>
#include <windows.h>

#include <cwchar>

#include "app_state.h"
#include "lm_studio.h"
#include "server.h"
#include "ui.h"

int WINAPI wWinMain(HINSTANCE instance, HINSTANCE, PWSTR commandLine, int) {
    HANDLE singleInstance = CreateMutexW(nullptr, TRUE, L"Local\\BurrowHost");
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        if (HWND running = FindWindowW(trayWindowClass, nullptr)) PostMessageW(running, openDevicesMessage, 0, 0);
        return 0;
    }

    WSADATA winsock{};
    if (WSAStartup(MAKEWORD(2, 2), &winsock) != 0) return 1;

    AppState state;
    if (state.LoadDevices() == LoadOutcome::Unreadable) {
        MessageBoxW(nullptr, L"Saved devices could not be read on this account, so phones need to pair again", L"Burrow", MB_OK | MB_ICONWARNING);
    }

    EnsureStartupConfigured();
    Server server(state);
    server.Start();

    bool launchedAtStartup = std::wcsstr(commandLine, L"--startup") != nullptr;
    int exitCode = RunUi(instance, state, !launchedAtStartup);

    server.Stop();
    state.SaveDevices();
    ModelStatus model = state.Model();
    if (model.state == ModelState::Loaded) lm_studio::Unload(model.instanceId);
    WSACleanup();
    CloseHandle(singleInstance);
    return exitCode;
}
