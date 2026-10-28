#pragma once

#include <windows.h>

#include "app_state.h"

constexpr wchar_t trayWindowClass[] = L"BurrowTray";
constexpr UINT openDevicesMessage = WM_APP + 3;

void EnsureStartupConfigured();
int RunUi(HINSTANCE instance, AppState& state, bool openDevices);
