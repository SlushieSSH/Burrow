#include "app_state.h"

#include <charconv>
#include <ctime>
#include <fstream>
#include <iterator>
#include <sstream>
#include <thread>

namespace {

constexpr size_t pairingCodeLength = 10;
constexpr uint64_t pairingLifetimeMilliseconds = 2 * 60 * 1000;
constexpr int maxPairingAttempts = 5;
constexpr size_t maxDeviceNameBytes = 48;
constexpr size_t maxDevices = 32;
// The phone computes the same two proofs; changing either label breaks pairing for every phone build that uses the old one.
constexpr std::string_view clientProofLabel = "burrow-pair-client";
constexpr std::string_view serverProofLabel = "burrow-pair-server";

int64_t UnixNow() {
    return static_cast<int64_t>(std::time(nullptr));
}

std::wstring DataDirectory() {
    wchar_t expanded[MAX_PATH];
    ExpandEnvironmentStringsW(L"%LOCALAPPDATA%\\Burrow", expanded, MAX_PATH);
    return expanded;
}

std::wstring DevicesPath() {
    return DataDirectory() + L"\\devices.bin";
}

std::string SanitizeName(std::string_view name) {
    std::string clean;
    for (char current : name) {
        if (static_cast<unsigned char>(current) < 0x20 || current == 0x7F) continue;
        clean += current;
    }
    if (clean.size() > maxDeviceNameBytes) {
        size_t cut = maxDeviceNameBytes;
        while (cut > 0 && (static_cast<unsigned char>(clean[cut]) & 0xC0) == 0x80) --cut;
        clean.resize(cut);
    }
    return clean.empty() ? std::string("Phone") : clean;
}

std::vector<uint8_t> Concatenate(std::initializer_list<std::span<const uint8_t>> parts) {
    std::vector<uint8_t> joined;
    for (std::span<const uint8_t> part : parts) joined.insert(joined.end(), part.begin(), part.end());
    return joined;
}

std::string Serialize(const std::vector<PairedDevice>& devices) {
    std::ostringstream output;
    output << "v1\n";
    for (const PairedDevice& device : devices) {
        output << device.id << '\t' << crypto::Hex(device.tokenHash) << '\t' << device.pairedAtUnix << '\t' << device.lastSeenUnix << '\t' << device.name << '\n';
    }
    return output.str();
}

std::optional<std::vector<PairedDevice>> Deserialize(std::string_view text) {
    std::vector<PairedDevice> devices;
    std::istringstream input{std::string(text)};
    std::string line;
    if (!std::getline(input, line) || line != "v1") return std::nullopt;
    while (std::getline(input, line)) {
        std::vector<std::string_view> fields;
        std::string_view remaining = line;
        for (int index = 0; index < 4; ++index) {
            size_t tab = remaining.find('\t');
            if (tab == std::string_view::npos) return std::nullopt;
            fields.push_back(remaining.substr(0, tab));
            remaining.remove_prefix(tab + 1);
        }
        PairedDevice device;
        device.id = fields[0];
        std::optional<std::vector<uint8_t>> hash = crypto::FromHex(fields[1]);
        if (!hash || hash->size() != device.tokenHash.size()) return std::nullopt;
        std::copy(hash->begin(), hash->end(), device.tokenHash.begin());
        if (std::from_chars(fields[2].data(), fields[2].data() + fields[2].size(), device.pairedAtUnix).ec != std::errc{}) return std::nullopt;
        if (std::from_chars(fields[3].data(), fields[3].data() + fields[3].size(), device.lastSeenUnix).ec != std::errc{}) return std::nullopt;
        device.name = remaining;
        devices.push_back(std::move(device));
    }
    return devices;
}

}

LoadOutcome AppState::LoadDevices() {
    std::ifstream file(DevicesPath(), std::ios::binary);
    if (!file) return LoadOutcome::NothingSaved;
    std::vector<uint8_t> sealed{std::istreambuf_iterator<char>(file), std::istreambuf_iterator<char>()};
    std::optional<std::vector<uint8_t>> plaintext = crypto::Unprotect(sealed);
    if (!plaintext) return LoadOutcome::Unreadable;
    std::optional<std::vector<PairedDevice>> loaded = Deserialize({reinterpret_cast<const char*>(plaintext->data()), plaintext->size()});
    SecureZeroMemory(plaintext->data(), plaintext->size());
    if (!loaded) return LoadOutcome::Unreadable;
    std::lock_guard guard(lock);
    devices = std::move(*loaded);
    return LoadOutcome::Loaded;
}

void AppState::SaveDevices() {
    std::lock_guard guard(lock);
    SaveLocked();
}

void AppState::SaveDevicesIfSeen() {
    std::lock_guard guard(lock);
    if (lastSeenDirty) SaveLocked();
}

void AppState::SaveLocked() {
    std::string plaintext = Serialize(devices);
    std::vector<uint8_t> sealed = crypto::Protect(crypto::AsBytes(plaintext));
    SecureZeroMemory(plaintext.data(), plaintext.size());
    CreateDirectoryW(DataDirectory().c_str(), nullptr);
    std::wstring finalPath = DevicesPath();
    std::wstring temporaryPath = finalPath + L".new";
    {
        std::ofstream file(temporaryPath, std::ios::binary | std::ios::trunc);
        file.write(reinterpret_cast<const char*>(sealed.data()), static_cast<std::streamsize>(sealed.size()));
        if (!file) return;
    }
    MoveFileExW(temporaryPath.c_str(), finalPath.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    lastSeenDirty = false;
}

void AppState::SetNotifyWindow(HWND window) {
    std::lock_guard guard(lock);
    notifyWindow = window;
}

void AppState::Notify() const {
    if (notifyWindow) PostMessageW(notifyWindow, stateChangedMessage, 0, 0);
}

std::vector<PairedDevice> AppState::Devices() const {
    std::lock_guard guard(lock);
    return devices;
}

bool AppState::Authenticate(std::string_view token) {
    crypto::Digest presented = crypto::Sha256(crypto::AsBytes(token));
    std::lock_guard guard(lock);
    PairedDevice* match = nullptr;
    for (PairedDevice& device : devices) {
        if (crypto::EqualConstantTime(device.tokenHash, presented)) match = &device;
    }
    if (!match) return false;
    match->lastSeenUnix = UnixNow();
    lastSeenDirty = true;
    Notify();
    return true;
}

void AppState::Unpair(std::string_view deviceId) {
    std::lock_guard guard(lock);
    std::erase_if(devices, [&](const PairedDevice& device) { return device.id == deviceId; });
    SaveLocked();
    Notify();
}

PairingCode AppState::StartPairing() {
    static constexpr char alphabet[] = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    std::vector<uint8_t> randomBytes = crypto::RandomBytes(pairingCodeLength);
    PairingCode started;
    for (uint8_t byte : randomBytes) started.code += alphabet[byte & 31];
    started.expiresAtTick = GetTickCount64() + pairingLifetimeMilliseconds;
    std::lock_guard guard(lock);
    pairing = started;
    failedPairingAttempts = 0;
    Notify();
    return started;
}

void AppState::CancelPairing() {
    std::lock_guard guard(lock);
    pairing.reset();
    Notify();
}

std::optional<PairingCode> AppState::ActivePairing() const {
    std::lock_guard guard(lock);
    if (pairing && GetTickCount64() >= pairing->expiresAtTick) return std::nullopt;
    return pairing;
}

std::optional<PairingGrant> AppState::CompletePairing(std::span<const uint8_t> clientProof, std::span<const uint8_t> fingerprint, std::string_view deviceName) {
    std::lock_guard guard(lock);
    if (!pairing || GetTickCount64() >= pairing->expiresAtTick || devices.size() >= maxDevices) {
        pairing.reset();
        Notify();
        return std::nullopt;
    }

    std::span<const uint8_t> codeKey = crypto::AsBytes(pairing->code);
    crypto::Digest expected = crypto::HmacSha256(codeKey, Concatenate({crypto::AsBytes(clientProofLabel), fingerprint}));
    if (!crypto::EqualConstantTime(expected, clientProof)) {
        if (++failedPairingAttempts >= maxPairingAttempts) pairing.reset();
        Notify();
        return std::nullopt;
    }

    std::string token = crypto::Base64Url(crypto::RandomBytes(32));
    crypto::Digest serverProof = crypto::HmacSha256(codeKey, Concatenate({crypto::AsBytes(serverProofLabel), fingerprint, crypto::AsBytes(token)}));

    PairedDevice device;
    device.id = crypto::Hex(crypto::RandomBytes(8));
    device.name = SanitizeName(deviceName);
    device.tokenHash = crypto::Sha256(crypto::AsBytes(token));
    device.pairedAtUnix = UnixNow();
    device.lastSeenUnix = device.pairedAtUnix;
    devices.push_back(std::move(device));
    pairing.reset();
    SaveLocked();
    Notify();
    return PairingGrant{std::move(token), crypto::Hex(serverProof)};
}

ModelStatus AppState::Model() const {
    std::lock_guard guard(lock);
    return model;
}

bool AppState::LoadModel(const AvailableModel& available, int contextLength) {
    std::string previousInstance = Model().instanceId;
    if (!BeginModelChange(ModelState::Loading, available.displayName)) return false;
    std::thread([this, key = available.key, contextLength, previousInstance] {
        if (!previousInstance.empty()) lm_studio::Unload(previousInstance);
        FinishLoading(lm_studio::Load(key, contextLength));
    }).detach();
    return true;
}

bool AppState::UnloadModel() {
    std::string instanceId = Model().instanceId;
    if (instanceId.empty() || !BeginModelChange(ModelState::Unloading, {})) return false;
    std::thread([this, instanceId] { FinishUnloading(lm_studio::Unload(instanceId)); }).detach();
    return true;
}

bool AppState::BeginModelChange(ModelState transitional, std::string_view displayName) {
    std::lock_guard guard(lock);
    bool busy = model.state == ModelState::Loading || model.state == ModelState::Unloading;
    if (busy) return false;
    model.state = transitional;
    if (transitional == ModelState::Loading) model.displayName = displayName;
    Notify();
    return true;
}

void AppState::FinishLoading(std::optional<LoadedModel> loaded) {
    std::lock_guard guard(lock);
    if (loaded) {
        model.state = ModelState::Loaded;
        model.instanceId = std::move(loaded->instanceId);
        model.contextLength = loaded->contextLength;
    } else {
        model = ModelStatus{};
    }
    Notify();
}

void AppState::FinishUnloading(bool unloaded) {
    std::lock_guard guard(lock);
    if (unloaded) {
        model = ModelStatus{};
    } else {
        model.state = ModelState::Loaded;
    }
    Notify();
}
