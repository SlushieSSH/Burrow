#pragma once

#include <windows.h>

#include <cstdint>
#include <mutex>
#include <optional>
#include <span>
#include <string>
#include <string_view>
#include <vector>

#include "crypto.h"
#include "lm_studio.h"

constexpr UINT stateChangedMessage = WM_APP + 1;

struct PairedDevice {
    std::string id;
    std::string name;
    crypto::Digest tokenHash{};
    int64_t pairedAtUnix = 0;
    int64_t lastSeenUnix = 0;
};

enum class ModelState { Unloaded, Loading, Loaded, Unloading };

struct ModelStatus {
    ModelState state = ModelState::Unloaded;
    std::string displayName;
    std::string instanceId;
    int contextLength = 0;
};

struct PairingCode {
    std::string code;
    uint64_t expiresAtTick = 0;
};

struct PairingGrant {
    std::string token;
    std::string serverProof;
};

enum class LoadOutcome { Loaded, NothingSaved, Unreadable };

class AppState {
public:
    LoadOutcome LoadDevices();
    void SaveDevices();
    void SaveDevicesIfSeen();
    void SetNotifyWindow(HWND window);

    std::vector<PairedDevice> Devices() const;
    bool Authenticate(std::string_view token);
    void Unpair(std::string_view deviceId);

    PairingCode StartPairing();
    void CancelPairing();
    std::optional<PairingCode> ActivePairing() const;
    std::optional<PairingGrant> CompletePairing(std::span<const uint8_t> clientProof, std::span<const uint8_t> fingerprint, std::string_view deviceName);

    ModelStatus Model() const;
    bool LoadModel(const AvailableModel& available, int contextLength);
    bool UnloadModel();

private:
    mutable std::mutex lock;
    std::vector<PairedDevice> devices;
    std::optional<PairingCode> pairing;
    int failedPairingAttempts = 0;
    ModelStatus model;
    bool lastSeenDirty = false;
    HWND notifyWindow = nullptr;

    bool BeginModelChange(ModelState transitional, std::string_view displayName);
    void FinishLoading(std::optional<LoadedModel> loaded);
    void FinishUnloading(bool unloaded);
    void Notify() const;
    void SaveLocked();
};
