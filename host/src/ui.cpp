#include "ui.h"

#include <windowsx.h>
#include <shellapi.h>
#include <dwmapi.h>
#include <uxtheme.h>

#include <algorithm>
#include <ctime>
#include <string>
#include <vector>

#include "lm_studio.h"
#include "tailscale.h"

namespace {

constexpr wchar_t devicesWindowClass[] = L"BurrowDevices";
constexpr wchar_t runKeyPath[] = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
constexpr wchar_t runValueName[] = L"Burrow";
constexpr wchar_t settingsKeyPath[] = L"Software\\Burrow";
constexpr wchar_t startupConfiguredValue[] = L"StartupConfigured";
constexpr UINT trayCallbackMessage = WM_APP + 2;
constexpr UINT trayIconId = 1;
constexpr UINT_PTR saveTimerId = 1;
constexpr UINT saveIntervalMilliseconds = 10 * 60 * 1000;
constexpr UINT_PTR refreshTimerId = 2;

enum TrayCommand : UINT {
    commandOpen = 1,
    commandUnload,
    commandPair,
    commandStartup,
    commandQuit,
    commandModelBase = 100,
};

enum TileCommand : UINT {
    commandUnpair = 1,
    commandCancelPairing,
};

constexpr COLORREF backgroundColor = RGB(0, 0, 0);
constexpr COLORREF tileColor = RGB(14, 14, 14);
constexpr COLORREF tileBorderColor = RGB(38, 38, 38);
constexpr COLORREF primaryTextColor = RGB(255, 255, 255);
constexpr COLORREF secondaryTextColor = RGB(255, 255, 255);
constexpr COLORREF menuDotColor = RGB(200, 200, 200);
constexpr COLORREF scrollThumbColor = RGB(70, 70, 70);

constexpr int titleHeight = 64;
constexpr int sideMargin = 16;
constexpr int tileHeight = 72;
constexpr int pairingTileHeight = 92;
constexpr int tileGap = 10;
constexpr int menuButtonWidth = 44;
constexpr int minTileWidth = 360;
constexpr int windowWidth = 1260;
constexpr int windowHeight = 540;

struct UiContext {
    HINSTANCE instance = nullptr;
    AppState* state = nullptr;
    HWND trayWindow = nullptr;
    HWND devicesWindow = nullptr;
    UINT taskbarCreatedMessage = 0;
};

UiContext ui;

struct DevicesView {
    int dpi = 96;
    int scrollOffset = 0;
    HFONT titleFont = nullptr;
    HFONT nameFont = nullptr;
    HFONT detailFont = nullptr;
    HFONT codeFont = nullptr;
    HBRUSH tileBrush = nullptr;
    HBRUSH borderBrush = nullptr;
    HBRUSH dotBrush = nullptr;
    HBRUSH thumbBrush = nullptr;
};

struct TileLayout {
    RECT tile{};
    RECT menuButton{};
    std::string deviceId;
    bool isPairing = false;
};

std::wstring Widen(std::string_view text) {
    int length = MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), nullptr, 0);
    std::wstring wide(static_cast<size_t>(length), L'\0');
    MultiByteToWideChar(CP_UTF8, 0, text.data(), static_cast<int>(text.size()), wide.data(), length);
    return wide;
}

int Scale(const DevicesView& view, int value) {
    return MulDiv(value, view.dpi, 96);
}

bool IsStartupRegistered() {
    HKEY key = nullptr;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, runKeyPath, 0, KEY_QUERY_VALUE, &key) != ERROR_SUCCESS) return false;
    bool present = RegQueryValueExW(key, runValueName, nullptr, nullptr, nullptr, nullptr) == ERROR_SUCCESS;
    RegCloseKey(key);
    return present;
}

void SetStartupRegistered(bool enabled) {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, runKeyPath, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &key, nullptr) != ERROR_SUCCESS) return;
    if (enabled) {
        wchar_t executable[MAX_PATH];
        GetModuleFileNameW(nullptr, executable, MAX_PATH);
        std::wstring command = L"\"" + std::wstring(executable) + L"\" --startup";
        RegSetValueExW(key, runValueName, 0, REG_SZ, reinterpret_cast<const BYTE*>(command.c_str()), static_cast<DWORD>((command.size() + 1) * sizeof(wchar_t)));
    } else {
        RegDeleteValueW(key, runValueName);
    }
    RegCloseKey(key);
}

std::wstring LastSeenText(int64_t lastSeenUnix) {
    int64_t elapsed = static_cast<int64_t>(std::time(nullptr)) - lastSeenUnix;
    if (elapsed < 120) return L"Active now";
    if (elapsed < 3600) return L"Last seen " + std::to_wstring(elapsed / 60) + L" min ago";
    if (elapsed < 86400) return L"Last seen " + std::to_wstring(elapsed / 3600) + L" h ago";
    return L"Last seen " + std::to_wstring(elapsed / 86400) + L" d ago";
}

std::wstring PairedDateText(int64_t pairedAtUnix) {
    ULARGE_INTEGER ticks{};
    ticks.QuadPart = static_cast<ULONGLONG>(pairedAtUnix) * 10000000ULL + 116444736000000000ULL;
    FILETIME fileTime{ticks.LowPart, ticks.HighPart};
    SYSTEMTIME universal{};
    SYSTEMTIME local{};
    FileTimeToSystemTime(&fileTime, &universal);
    SystemTimeToTzSpecificLocalTime(nullptr, &universal, &local);
    wchar_t formatted[64];
    GetDateFormatEx(LOCALE_NAME_USER_DEFAULT, 0, &local, L"d MMM yyyy", formatted, 64, nullptr);
    return std::wstring(L"Paired ") + formatted;
}

std::wstring CountdownText(uint64_t expiresAtTick) {
    uint64_t now = GetTickCount64();
    uint64_t remainingSeconds = expiresAtTick > now ? (expiresAtTick - now + 999) / 1000 : 0;
    wchar_t formatted[16];
    swprintf_s(formatted, L"%llu:%02llu", remainingSeconds / 60, remainingSeconds % 60);
    return formatted;
}

std::wstring FormattedCode(std::string_view code) {
    std::wstring wide = Widen(code);
    return wide.substr(0, 5) + L"-" + wide.substr(5);
}

HFONT MakeFont(const DevicesView& view, int points, int weight, const wchar_t* face) {
    return CreateFontW(-MulDiv(points, view.dpi, 72), 0, 0, 0, weight, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS, CLEARTYPE_QUALITY, DEFAULT_PITCH, face);
}

void CreateFonts(DevicesView& view) {
    view.titleFont = MakeFont(view, 14, FW_SEMIBOLD, L"Bahnschrift SemiBold");
    view.nameFont = MakeFont(view, 12, FW_SEMIBOLD, L"Bahnschrift SemiBold");
    view.detailFont = MakeFont(view, 10, FW_NORMAL, L"Bahnschrift");
    view.codeFont = MakeFont(view, 18, FW_SEMIBOLD, L"Cascadia Mono");
}

void DeleteFonts(DevicesView& view) {
    for (HFONT* font : {&view.titleFont, &view.nameFont, &view.detailFont, &view.codeFont}) {
        if (*font) DeleteObject(*font);
        *font = nullptr;
    }
}

int ColumnCount(const DevicesView& view, const RECT& client) {
    int usable = client.right - 2 * Scale(view, sideMargin) + Scale(view, tileGap);
    return std::max(1, usable / Scale(view, minTileWidth + tileGap));
}

int ContentHeight(const DevicesView& view, const RECT& client, size_t deviceCount, bool pairing) {
    int columns = ColumnCount(view, client);
    int rows = static_cast<int>((deviceCount + columns - 1) / columns);
    int height = Scale(view, tileGap);
    if (pairing) height += Scale(view, pairingTileHeight + tileGap);
    height += rows * Scale(view, tileHeight + tileGap);
    return height;
}

TileLayout MakeTile(const DevicesView& view, int left, int top, int right, int height, std::string deviceId, bool isPairing) {
    TileLayout layout;
    layout.tile = {left, top, right, top + Scale(view, height)};
    layout.menuButton = {right - Scale(view, menuButtonWidth), top, right, top + Scale(view, height)};
    layout.deviceId = std::move(deviceId);
    layout.isPairing = isPairing;
    return layout;
}

std::vector<TileLayout> LayoutTiles(const DevicesView& view, const RECT& client, const std::vector<PairedDevice>& devices, bool pairing) {
    std::vector<TileLayout> tiles;
    int left = Scale(view, sideMargin);
    int right = client.right - Scale(view, sideMargin);
    int gap = Scale(view, tileGap);
    int top = Scale(view, titleHeight) + gap - view.scrollOffset;
    if (pairing) {
        tiles.push_back(MakeTile(view, left, top, right, pairingTileHeight, {}, true));
        top += Scale(view, pairingTileHeight) + gap;
    }
    int columns = ColumnCount(view, client);
    int tileWidth = (right - left - gap * (columns - 1)) / columns;
    for (size_t index = 0; index < devices.size(); ++index) {
        int column = static_cast<int>(index % columns);
        int row = static_cast<int>(index / columns);
        int tileLeft = left + column * (tileWidth + gap);
        int tileTop = top + row * (Scale(view, tileHeight) + gap);
        tiles.push_back(MakeTile(view, tileLeft, tileTop, tileLeft + tileWidth, tileHeight, devices[index].id, false));
    }
    return tiles;
}

int MaxScroll(const DevicesView& view, const RECT& client, size_t deviceCount, bool pairing) {
    int visible = client.bottom - Scale(view, titleHeight);
    return std::max(0, ContentHeight(view, client, deviceCount, pairing) - visible);
}

void DrawText(HDC canvas, HFONT font, COLORREF color, const std::wstring& text, RECT bounds, UINT format) {
    SelectObject(canvas, font);
    SetTextColor(canvas, color);
    ::DrawTextW(canvas, text.c_str(), static_cast<int>(text.size()), &bounds, format | DT_NOPREFIX | DT_SINGLELINE);
}

void DrawMenuDots(HDC canvas, const DevicesView& view, const RECT& button) {
    int centerX = (button.left + button.right) / 2;
    int centerY = (button.top + button.bottom) / 2;
    int radius = std::max(2, Scale(view, 2));
    int spacing = Scale(view, 7);
    HGDIOBJ previousBrush = SelectObject(canvas, view.dotBrush);
    HGDIOBJ previousPen = SelectObject(canvas, GetStockObject(NULL_PEN));
    for (int offset : {-spacing, 0, spacing}) {
        Ellipse(canvas, centerX + offset - radius, centerY - radius, centerX + offset + radius + 1, centerY + radius + 1);
    }
    SelectObject(canvas, previousPen);
    SelectObject(canvas, previousBrush);
}

void DrawTileFrame(HDC canvas, const DevicesView& view, const RECT& tile) {
    FillRect(canvas, &tile, view.tileBrush);
    FrameRect(canvas, &tile, view.borderBrush);
}

void PaintDevices(HWND window, DevicesView& view) {
    PAINTSTRUCT paint;
    HDC target = BeginPaint(window, &paint);
    RECT client;
    GetClientRect(window, &client);

    HDC canvas = CreateCompatibleDC(target);
    HBITMAP bitmap = CreateCompatibleBitmap(target, client.right, client.bottom);
    HGDIOBJ previousBitmap = SelectObject(canvas, bitmap);
    HGDIOBJ previousFont = SelectObject(canvas, view.titleFont);
    FillRect(canvas, &client, static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH)));
    SetBkMode(canvas, TRANSPARENT);

    std::vector<PairedDevice> devices = ui.state->Devices();
    std::optional<PairingCode> pairing = ui.state->ActivePairing();
    int titleBottom = Scale(view, titleHeight);

    IntersectClipRect(canvas, 0, titleBottom, client.right, client.bottom);
    std::vector<TileLayout> tiles = LayoutTiles(view, client, devices, pairing.has_value());
    int padding = Scale(view, 16);
    for (const TileLayout& layout : tiles) {
        if (layout.tile.bottom < titleBottom || layout.tile.top > client.bottom) continue;
        DrawTileFrame(canvas, view, layout.tile);
        RECT textArea{layout.tile.left + padding, layout.tile.top, layout.menuButton.left, layout.tile.bottom};
        if (layout.isPairing) {
            RECT label{textArea.left, textArea.top + Scale(view, 14), textArea.right, textArea.top + Scale(view, 34)};
            std::optional<std::wstring> address = TailscaleAddressText();
            DrawText(canvas, view.detailFont, secondaryTextColor, address ? L"Pairing code  ·  PC address " + *address : std::wstring(L"Pairing code"), label, DT_LEFT | DT_VCENTER);
            RECT code{textArea.left, label.bottom + Scale(view, 4), textArea.right, layout.tile.bottom - Scale(view, 14)};
            DrawText(canvas, view.codeFont, primaryTextColor, FormattedCode(pairing->code), code, DT_LEFT | DT_VCENTER);
            DrawText(canvas, view.detailFont, secondaryTextColor, CountdownText(pairing->expiresAtTick), code, DT_RIGHT | DT_VCENTER);
        } else {
            auto device = std::find_if(devices.begin(), devices.end(), [&](const PairedDevice& candidate) { return candidate.id == layout.deviceId; });
            RECT name{textArea.left, textArea.top + Scale(view, 14), textArea.right, textArea.top + Scale(view, 38)};
            DrawText(canvas, view.nameFont, primaryTextColor, Widen(device->name), name, DT_LEFT | DT_VCENTER | DT_END_ELLIPSIS);
            RECT detail{textArea.left, name.bottom + Scale(view, 2), textArea.right, layout.tile.bottom - Scale(view, 12)};
            DrawText(canvas, view.detailFont, secondaryTextColor, LastSeenText(device->lastSeenUnix) + L"  ·  " + PairedDateText(device->pairedAtUnix), detail, DT_LEFT | DT_VCENTER | DT_END_ELLIPSIS);
        }
        DrawMenuDots(canvas, view, layout.menuButton);
    }

    if (tiles.empty()) {
        RECT empty{0, titleBottom, client.right, client.bottom - titleBottom};
        DrawText(canvas, view.detailFont, secondaryTextColor, L"No devices", empty, DT_CENTER | DT_VCENTER);
    }

    int maxScroll = MaxScroll(view, client, devices.size(), pairing.has_value());
    if (maxScroll > 0) {
        int trackHeight = client.bottom - titleBottom;
        int contentHeight = ContentHeight(view, client, devices.size(), pairing.has_value());
        int thumbHeight = std::max(Scale(view, 24), MulDiv(trackHeight, trackHeight, contentHeight));
        int thumbTop = titleBottom + MulDiv(trackHeight - thumbHeight, view.scrollOffset, maxScroll);
        RECT thumb{client.right - Scale(view, 5), thumbTop, client.right - Scale(view, 2), thumbTop + thumbHeight};
        FillRect(canvas, &thumb, view.thumbBrush);
    }

    SelectClipRgn(canvas, nullptr);
    RECT title{0, 0, client.right, titleBottom};
    DrawText(canvas, view.titleFont, primaryTextColor, L"Connected devices", title, DT_CENTER | DT_VCENTER);

    BitBlt(target, 0, 0, client.right, client.bottom, canvas, 0, 0, SRCCOPY);
    SelectObject(canvas, previousFont);
    SelectObject(canvas, previousBitmap);
    DeleteObject(bitmap);
    DeleteDC(canvas);
    EndPaint(window, &paint);
}

void ClampScroll(HWND window, DevicesView& view) {
    RECT client;
    GetClientRect(window, &client);
    int maxScroll = MaxScroll(view, client, ui.state->Devices().size(), ui.state->ActivePairing().has_value());
    view.scrollOffset = std::clamp(view.scrollOffset, 0, maxScroll);
}

void ShowTileMenu(HWND window, const TileLayout& layout) {
    HMENU menu = CreatePopupMenu();
    if (layout.isPairing) {
        AppendMenuW(menu, MF_STRING, commandCancelPairing, L"Cancel pairing");
    } else {
        AppendMenuW(menu, MF_STRING, commandUnpair, L"Force unpair");
    }
    POINT anchor{layout.menuButton.left, layout.menuButton.bottom};
    ClientToScreen(window, &anchor);
    UINT command = TrackPopupMenuEx(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY, anchor.x, anchor.y, window, nullptr);
    DestroyMenu(menu);
    if (command == commandUnpair) ui.state->Unpair(layout.deviceId);
    if (command == commandCancelPairing) ui.state->CancelPairing();
}

void ResetRefreshTimer(HWND window) {
    UINT interval = ui.state->ActivePairing() ? 1000 : 30 * 1000;
    SetTimer(window, refreshTimerId, interval, nullptr);
}

void ApplyDarkFrame(HWND window) {
    BOOL darkMode = TRUE;
    DwmSetWindowAttribute(window, DWMWA_USE_IMMERSIVE_DARK_MODE, &darkMode, sizeof darkMode);
    COLORREF caption = backgroundColor;
    DwmSetWindowAttribute(window, DWMWA_CAPTION_COLOR, &caption, sizeof caption);
    COLORREF border = tileBorderColor;
    DwmSetWindowAttribute(window, DWMWA_BORDER_COLOR, &border, sizeof border);
    WTA_OPTIONS options{WTNCA_NODRAWCAPTION | WTNCA_NODRAWICON, WTNCA_NODRAWCAPTION | WTNCA_NODRAWICON};
    SetWindowThemeAttribute(window, WTA_NONCLIENT, &options, sizeof options);
}

LRESULT CALLBACK DevicesProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    auto* view = reinterpret_cast<DevicesView*>(GetWindowLongPtrW(window, GWLP_USERDATA));
    switch (message) {
    case WM_CREATE: {
        auto* created = new DevicesView();
        created->dpi = static_cast<int>(GetDpiForWindow(window));
        CreateFonts(*created);
        created->tileBrush = CreateSolidBrush(tileColor);
        created->borderBrush = CreateSolidBrush(tileBorderColor);
        created->dotBrush = CreateSolidBrush(menuDotColor);
        created->thumbBrush = CreateSolidBrush(scrollThumbColor);
        SetWindowLongPtrW(window, GWLP_USERDATA, reinterpret_cast<LONG_PTR>(created));
        ApplyDarkFrame(window);
        ResetRefreshTimer(window);
        return 0;
    }
    case WM_ERASEBKGND:
        return 1;
    case WM_PAINT:
        PaintDevices(window, *view);
        return 0;
    case WM_SIZE:
        ClampScroll(window, *view);
        InvalidateRect(window, nullptr, FALSE);
        return 0;
    case WM_MOUSEWHEEL:
        view->scrollOffset -= MulDiv(GET_WHEEL_DELTA_WPARAM(wParam), Scale(*view, tileHeight), WHEEL_DELTA);
        ClampScroll(window, *view);
        InvalidateRect(window, nullptr, FALSE);
        return 0;
    case WM_LBUTTONUP: {
        POINT click{GET_X_LPARAM(lParam), GET_Y_LPARAM(lParam)};
        RECT client;
        GetClientRect(window, &client);
        if (click.y < Scale(*view, titleHeight)) return 0;
        std::vector<TileLayout> tiles = LayoutTiles(*view, client, ui.state->Devices(), ui.state->ActivePairing().has_value());
        for (const TileLayout& layout : tiles) {
            if (PtInRect(&layout.menuButton, click)) {
                ShowTileMenu(window, layout);
                return 0;
            }
        }
        return 0;
    }
    case WM_TIMER:
        if (wParam == refreshTimerId) {
            ResetRefreshTimer(window);
            InvalidateRect(window, nullptr, FALSE);
        }
        return 0;
    case stateChangedMessage:
        ClampScroll(window, *view);
        ResetRefreshTimer(window);
        InvalidateRect(window, nullptr, FALSE);
        return 0;
    case WM_DPICHANGED: {
        view->dpi = HIWORD(wParam);
        DeleteFonts(*view);
        CreateFonts(*view);
        const RECT* suggested = reinterpret_cast<const RECT*>(lParam);
        SetWindowPos(window, nullptr, suggested->left, suggested->top, suggested->right - suggested->left, suggested->bottom - suggested->top, SWP_NOZORDER | SWP_NOACTIVATE);
        return 0;
    }
    case WM_CLOSE:
        DestroyWindow(window);
        return 0;
    case WM_DESTROY:
        KillTimer(window, refreshTimerId);
        DeleteFonts(*view);
        for (HBRUSH brush : {view->tileBrush, view->borderBrush, view->dotBrush, view->thumbBrush}) DeleteObject(brush);
        delete view;
        SetWindowLongPtrW(window, GWLP_USERDATA, 0);
        ui.devicesWindow = nullptr;
        return 0;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

void OpenDevicesWindow() {
    if (ui.devicesWindow) {
        ShowWindow(ui.devicesWindow, SW_RESTORE);
        SetForegroundWindow(ui.devicesWindow);
        return;
    }
    HMONITOR monitor = MonitorFromPoint({0, 0}, MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO monitorInfo{sizeof monitorInfo};
    GetMonitorInfoW(monitor, &monitorInfo);
    UINT dpi = 96;
    UINT ignored = 96;
    using GetDpiForMonitorFunction = HRESULT(WINAPI*)(HMONITOR, int, UINT*, UINT*);
    if (HMODULE shcore = LoadLibraryExW(L"shcore.dll", nullptr, LOAD_LIBRARY_SEARCH_SYSTEM32)) {
        if (auto getDpi = reinterpret_cast<GetDpiForMonitorFunction>(GetProcAddress(shcore, "GetDpiForMonitor"))) getDpi(monitor, 0, &dpi, &ignored);
        FreeLibrary(shcore);
    }
    const RECT& work = monitorInfo.rcWork;
    int width = std::min(MulDiv(windowWidth, static_cast<int>(dpi), 96), static_cast<int>(work.right - work.left) * 9 / 10);
    int height = std::min(MulDiv(windowHeight, static_cast<int>(dpi), 96), width * 9 / 21);
    int left = work.left + (work.right - work.left - width) / 2;
    int top = work.top + (work.bottom - work.top - height) / 2;
    ui.devicesWindow = CreateWindowExW(0, devicesWindowClass, L"Connected devices", WS_OVERLAPPEDWINDOW, left, top, width, height, nullptr, nullptr, ui.instance, nullptr);
    ShowWindow(ui.devicesWindow, SW_SHOWNORMAL);
    SetForegroundWindow(ui.devicesWindow);
}

void AddTrayIcon(HWND window) {
    NOTIFYICONDATAW icon{};
    icon.cbSize = sizeof icon;
    icon.hWnd = window;
    icon.uID = trayIconId;
    icon.uFlags = NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP;
    icon.uCallbackMessage = trayCallbackMessage;
    icon.hIcon = static_cast<HICON>(LoadImageW(ui.instance, MAKEINTRESOURCEW(1), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), LR_SHARED));
    wcscpy_s(icon.szTip, L"Burrow");
    Shell_NotifyIconW(NIM_ADD, &icon);
    icon.uVersion = NOTIFYICON_VERSION_4;
    Shell_NotifyIconW(NIM_SETVERSION, &icon);
}

void RemoveTrayIcon(HWND window) {
    NOTIFYICONDATAW icon{};
    icon.cbSize = sizeof icon;
    icon.hWnd = window;
    icon.uID = trayIconId;
    Shell_NotifyIconW(NIM_DELETE, &icon);
}

std::wstring ModelStatusText(const ModelStatus& model, lm_studio::ServerCheck server) {
    switch (model.state) {
    case ModelState::Loading: return L"Loading " + Widen(model.displayName);
    case ModelState::Loaded: return L"Loaded " + Widen(model.displayName);
    case ModelState::Unloading: return L"Unloading";
    default: break;
    }
    switch (server) {
    case lm_studio::ServerCheck::NotRunning: return L"Open LM Studio to load a model";
    case lm_studio::ServerCheck::NotLmStudio: return L"Port 1234 is taken by another app";
    case lm_studio::ServerCheck::OpenToNetwork: return L"Turn off Serve on local network in LM Studio";
    default: return L"No model loaded";
    }
}

void ShowTrayMenu(HWND window, POINT anchor) {
    lm_studio::ModelList available = lm_studio::ListModels();
    const std::vector<AvailableModel>& models = available.models;
    ModelStatus model = ui.state->Model();
    bool settled = model.state == ModelState::Loaded || model.state == ModelState::Unloaded;
    bool canLoad = settled && available.server == lm_studio::ServerCheck::Ready;

    HMENU modelsMenu = CreatePopupMenu();
    for (size_t index = 0; index < models.size(); ++index) {
        AppendMenuW(modelsMenu, MF_STRING, commandModelBase + index, Widen(models[index].displayName).c_str());
    }
    if (models.empty()) AppendMenuW(modelsMenu, MF_STRING | MF_GRAYED, 0, L"No models downloaded");

    HMENU menu = CreatePopupMenu();
    AppendMenuW(menu, MF_STRING, commandOpen, L"Connected devices");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING | MF_GRAYED, 0, ModelStatusText(model, available.server).c_str());
    AppendMenuW(menu, MF_POPUP | (canLoad ? 0 : MF_GRAYED), reinterpret_cast<UINT_PTR>(modelsMenu), L"Load model");
    AppendMenuW(menu, MF_STRING | (model.state == ModelState::Loaded ? 0 : MF_GRAYED), commandUnload, L"Unload model");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, commandPair, L"Pair phone");
    AppendMenuW(menu, MF_STRING | (IsStartupRegistered() ? MF_CHECKED : 0), commandStartup, L"Start with Windows");
    AppendMenuW(menu, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(menu, MF_STRING, commandQuit, L"Quit");

    SetForegroundWindow(window);
    UINT command = TrackPopupMenuEx(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON | TPM_NONOTIFY, anchor.x, anchor.y, window, nullptr);
    DestroyMenu(menu);
    PostMessageW(window, WM_NULL, 0, 0);

    if (command >= commandModelBase && command - commandModelBase < models.size()) {
        ui.state->LoadModel(models[command - commandModelBase], 0);
        return;
    }
    switch (command) {
    case commandOpen:
        OpenDevicesWindow();
        break;
    case commandUnload:
        ui.state->UnloadModel();
        break;
    case commandPair:
        ui.state->StartPairing();
        OpenDevicesWindow();
        break;
    case commandStartup:
        SetStartupRegistered(!IsStartupRegistered());
        break;
    case commandQuit:
        if (ui.devicesWindow) DestroyWindow(ui.devicesWindow);
        DestroyWindow(window);
        break;
    }
}

LRESULT CALLBACK TrayProc(HWND window, UINT message, WPARAM wParam, LPARAM lParam) {
    if (message == ui.taskbarCreatedMessage && ui.taskbarCreatedMessage != 0) {
        AddTrayIcon(window);
        return 0;
    }
    switch (message) {
    case WM_CREATE:
        AddTrayIcon(window);
        SetTimer(window, saveTimerId, saveIntervalMilliseconds, nullptr);
        return 0;
    case trayCallbackMessage:
        switch (LOWORD(lParam)) {
        case NIN_SELECT:
        case NIN_KEYSELECT:
            OpenDevicesWindow();
            return 0;
        case WM_CONTEXTMENU:
            ShowTrayMenu(window, {GET_X_LPARAM(wParam), GET_Y_LPARAM(wParam)});
            return 0;
        }
        return 0;
    case openDevicesMessage:
        OpenDevicesWindow();
        return 0;
    case stateChangedMessage:
        if (ui.devicesWindow) PostMessageW(ui.devicesWindow, stateChangedMessage, 0, 0);
        return 0;
    case WM_TIMER:
        if (wParam == saveTimerId) ui.state->SaveDevicesIfSeen();
        return 0;
    case WM_DESTROY:
        KillTimer(window, saveTimerId);
        RemoveTrayIcon(window);
        PostQuitMessage(0);
        return 0;
    }
    return DefWindowProcW(window, message, wParam, lParam);
}

}

void EnsureStartupConfigured() {
    HKEY key = nullptr;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, settingsKeyPath, 0, nullptr, 0, KEY_QUERY_VALUE | KEY_SET_VALUE, nullptr, &key, nullptr) != ERROR_SUCCESS) return;
    bool configured = RegQueryValueExW(key, startupConfiguredValue, nullptr, nullptr, nullptr, nullptr) == ERROR_SUCCESS;
    if (!configured) {
        SetStartupRegistered(true);
        DWORD marker = 1;
        RegSetValueExW(key, startupConfiguredValue, 0, REG_DWORD, reinterpret_cast<const BYTE*>(&marker), sizeof marker);
    }
    RegCloseKey(key);
}

int RunUi(HINSTANCE instance, AppState& state, bool openDevices) {
    ui.instance = instance;
    ui.state = &state;
    ui.taskbarCreatedMessage = RegisterWindowMessageW(L"TaskbarCreated");

    WNDCLASSEXW trayClass{sizeof trayClass};
    trayClass.lpfnWndProc = TrayProc;
    trayClass.hInstance = instance;
    trayClass.lpszClassName = trayWindowClass;
    RegisterClassExW(&trayClass);

    WNDCLASSEXW devicesClass{sizeof devicesClass};
    devicesClass.lpfnWndProc = DevicesProc;
    devicesClass.hInstance = instance;
    devicesClass.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    devicesClass.hbrBackground = static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));
    devicesClass.hIcon = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(1), IMAGE_ICON, 0, 0, LR_DEFAULTSIZE | LR_SHARED));
    devicesClass.hIconSm = static_cast<HICON>(LoadImageW(instance, MAKEINTRESOURCEW(1), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), LR_SHARED));
    devicesClass.lpszClassName = devicesWindowClass;
    RegisterClassExW(&devicesClass);

    ui.trayWindow = CreateWindowExW(0, trayWindowClass, L"Burrow", WS_OVERLAPPED, 0, 0, 0, 0, nullptr, nullptr, instance, nullptr);
    state.SetNotifyWindow(ui.trayWindow);
    if (openDevices) OpenDevicesWindow();

    MSG message;
    while (GetMessageW(&message, nullptr, 0, 0) > 0) {
        TranslateMessage(&message);
        DispatchMessageW(&message);
    }
    state.SetNotifyWindow(nullptr);
    return static_cast<int>(message.wParam);
}
