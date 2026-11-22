#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <dlfcn.h>
#include <fstream>
#include <mutex>
#include <sstream>
#include <string>
#include <sys/system_properties.h>
#include <unistd.h>
#include <vector>

namespace codm::native_engine {

constexpr int32_t kThermalStatusUnsupported = -1;

struct MemorySnapshot {
    double totalMb{0.0};
    double availableMb{0.0};
    double usagePercent{0.0};
    bool valid{false};
};

struct ThermalSnapshot {
    int32_t status{kThermalStatusUnsupported};
    float headroomForecast5s{-1.0f};
    bool supported{false};
};

struct FrameStatsSnapshot {
    double avgFrameTimeMs{0.0};
    double jitterMs{0.0};
    double p99FrameTimeMs{0.0};
    uint64_t totalFrames{0};
};

struct NetworkStatsSnapshot {
    double avgRttMs{0.0};
    double jitterMs{0.0};
    uint64_t totalRxBytes{0};
    uint64_t totalTxBytes{0};
};

struct TelemetrySnapshot {
    int32_t apiLevel{0};
    ThermalSnapshot thermal{};
    MemorySnapshot memory{};
    bool adpfSupported{false};
    bool adpfSessionActive{false};
    int64_t adpfPreferredRateNanos{-1};
    FrameStatsSnapshot frameStats{};
    NetworkStatsSnapshot networkStats{};
    int32_t onlineCpuCores{1};
};

/**
 * Lock-free-friendly fixed-capacity ring buffer for jitter & latency samples.
 * Stores integer microsecond samples in atomic slots so UI telemetry polling
 * never contends with hot-path render/network threads.
 */
template <size_t Capacity>
class AtomicSampleRing {
    static_assert((Capacity & (Capacity - 1)) == 0, "Capacity must be a power of 2");

public:
    void pushMicros(uint64_t valueUs) noexcept {
        const uint64_t idx = writeIndex_.fetch_add(1, std::memory_order_relaxed);
        slots_[idx & (Capacity - 1)].store(valueUs, std::memory_order_relaxed);
    }

    [[nodiscard]] size_t snapshot(std::array<double, Capacity>& outMs) const noexcept {
        const uint64_t written = writeIndex_.load(std::memory_order_acquire);
        const size_t count = static_cast<size_t>(std::min<uint64_t>(written, Capacity));
        if (count == 0) {
            return 0;
        }
        const uint64_t start = written - count;
        for (size_t i = 0; i < count; ++i) {
            const uint64_t rawUs = slots_[(start + i) & (Capacity - 1)].load(std::memory_order_relaxed);
            outMs[i] = static_cast<double>(rawUs) / 1000.0;
        }
        return count;
    }

    [[nodiscard]] uint64_t countTotal() const noexcept {
        return writeIndex_.load(std::memory_order_acquire);
    }

    void reset() noexcept {
        writeIndex_.store(0, std::memory_order_release);
        for (auto& slot : slots_) {
            slot.store(0, std::memory_order_relaxed);
        }
    }

private:
    std::array<std::atomic<uint64_t>, Capacity> slots_{};
    std::atomic<uint64_t> writeIndex_{0};
};

/**
 * C++20 Hardware Telemetry, ADPF & Thermal Engine.
 *
 * Security & SELinux compliance:
 * - Strictly avoids reading /sys/class/thermal/ (blocked on Android 14-16).
 * - Uses official NDK AThermalManager & APerformanceHintManager loaded via dlsym
 *   for safe compatibility across minSdk 24 through Android 16+.
 */
class TelemetryEngine {
public:
    TelemetryEngine() = default;
    ~TelemetryEngine() { shutdown(); }

    TelemetryEngine(const TelemetryEngine&) = delete;
    TelemetryEngine& operator=(const TelemetryEngine&) = delete;

    bool initialize() {
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        if (initialized_.load(std::memory_order_acquire)) {
            return true;
        }

        apiLevel_ = detectApiLevel();
        loadPlatformSymbols();

        if (apiLevel_ >= 30 && fnThermalAcquire_ != nullptr) {
            thermalManager_ = fnThermalAcquire_();
        }
        if (apiLevel_ >= 33 && fnPerfHintGetManager_ != nullptr) {
            perfHintManager_ = fnPerfHintGetManager_();
        }

        initialized_.store(true, std::memory_order_release);
        return true;
    }

    void shutdown() {
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        stopAdpfSessionLocked();

        if (thermalManager_ != nullptr && fnThermalRelease_ != nullptr) {
            fnThermalRelease_(thermalManager_);
            thermalManager_ = nullptr;
        }

        if (libAndroidHandle_ != nullptr) {
            dlclose(libAndroidHandle_);
            libAndroidHandle_ = nullptr;
        }

        fnThermalAcquire_ = nullptr;
        fnThermalRelease_ = nullptr;
        fnThermalGetStatus_ = nullptr;
        fnThermalGetHeadroom_ = nullptr;
        fnPerfHintGetManager_ = nullptr;
        fnPerfHintCreateSession_ = nullptr;
        fnPerfHintGetPreferredRate_ = nullptr;
        fnPerfHintUpdateTarget_ = nullptr;
        fnPerfHintReportActual_ = nullptr;
        fnPerfHintCloseSession_ = nullptr;
        perfHintManager_ = nullptr;

        initialized_.store(false, std::memory_order_release);
    }

    bool startAdpfSession(int32_t targetFps, const std::vector<int32_t>& threadIds) {
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        if (!initialized_.load(std::memory_order_acquire)) {
            return false;
        }
        if (perfHintManager_ == nullptr || fnPerfHintCreateSession_ == nullptr || threadIds.empty()) {
            return false;
        }

        stopAdpfSessionLocked();

        const int32_t clampedFps = std::clamp(targetFps, 30, 144);
        const int64_t targetNanos = 1'000'000'000LL / static_cast<int64_t>(clampedFps);

        perfHintSession_ = fnPerfHintCreateSession_(
            perfHintManager_,
            threadIds.data(),
            threadIds.size(),
            targetNanos
        );
        adpfActive_.store(perfHintSession_ != nullptr, std::memory_order_release);
        return perfHintSession_ != nullptr;
    }

    bool updateAdpfTargetFps(int32_t targetFps) {
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        if (perfHintSession_ == nullptr || fnPerfHintUpdateTarget_ == nullptr) {
            return false;
        }
        const int32_t clampedFps = std::clamp(targetFps, 30, 144);
        const int64_t targetNanos = 1'000'000'000LL / static_cast<int64_t>(clampedFps);
        return fnPerfHintUpdateTarget_(perfHintSession_, targetNanos) == 0;
    }

    bool reportAdpfWorkDuration(int64_t actualDurationNanos) {
        if (!adpfActive_.load(std::memory_order_acquire) || actualDurationNanos <= 0) {
            return false;
        }
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        if (perfHintSession_ == nullptr || fnPerfHintReportActual_ == nullptr) {
            return false;
        }
        return fnPerfHintReportActual_(perfHintSession_, actualDurationNanos) == 0;
    }

    void stopAdpfSession() {
        std::lock_guard<std::mutex> lock(lifecycleMutex_);
        stopAdpfSessionLocked();
    }

    void recordFrameDurationNanos(int64_t durationNanos) {
        if (durationNanos <= 0) {
            return;
        }
        const uint64_t clampedUs = static_cast<uint64_t>(
            std::clamp<int64_t>(durationNanos / 1000LL, 100LL, 250'000LL)
        );
        frameRing_.pushMicros(clampedUs);

        if (adpfActive_.load(std::memory_order_acquire)) {
            reportAdpfWorkDuration(durationNanos);
        }
    }

    void recordNetworkSample(double rttMs, uint64_t rxBytes, uint64_t txBytes) {
        if (rttMs >= 0.0 && std::isfinite(rttMs)) {
            const uint64_t us = static_cast<uint64_t>(std::clamp(rttMs * 1000.0, 0.0, 5'000'000.0));
            netRttRing_.pushMicros(us);

            const double prevRtt = lastNetRttMs_.exchange(rttMs, std::memory_order_relaxed);
            if (prevRtt >= 0.0) {
                const double diff = std::abs(rttMs - prevRtt);
                // RFC 3550 smoothed jitter estimator: J = J + (|D| - J) / 16
                double currentJ = smoothedNetJitterMs_.load(std::memory_order_relaxed);
                currentJ += (diff - currentJ) / 16.0;
                smoothedNetJitterMs_.store(currentJ, std::memory_order_relaxed);
            }
        }
        totalRxBytes_.store(rxBytes, std::memory_order_relaxed);
        totalTxBytes_.store(txBytes, std::memory_order_relaxed);
    }

    [[nodiscard]] TelemetrySnapshot collectSnapshot() const {
        TelemetrySnapshot snap{};
        snap.apiLevel = apiLevel_ > 0 ? apiLevel_ : detectApiLevel();
        snap.thermal = readThermalSnapshot();
        snap.memory = readProcMemInfo();
        snap.adpfSupported = (perfHintManager_ != nullptr && fnPerfHintCreateSession_ != nullptr);
        snap.adpfSessionActive = adpfActive_.load(std::memory_order_acquire);

        if (perfHintManager_ != nullptr && fnPerfHintGetPreferredRate_ != nullptr) {
            snap.adpfPreferredRateNanos = fnPerfHintGetPreferredRate_(perfHintManager_);
        }

        snap.frameStats = computeFrameStats();
        snap.networkStats = computeNetworkStats();

        const long cores = sysconf(_SC_NPROCESSORS_ONLN);
        snap.onlineCpuCores = cores > 0 ? static_cast<int32_t>(cores) : 1;
        return snap;
    }

private:
    using FnThermalAcquire = void* (*)();
    using FnThermalRelease = void (*)(void*);
    using FnThermalGetStatus = int32_t (*)(void*);
    using FnThermalGetHeadroom = float (*)(void*, int32_t);

    using FnPerfHintGetManager = void* (*)();
    using FnPerfHintCreateSession = void* (*)(void*, const int32_t*, size_t, int64_t);
    using FnPerfHintGetPreferredRate = int64_t (*)(void*);
    using FnPerfHintUpdateTarget = int (*)(void*, int64_t);
    using FnPerfHintReportActual = int (*)(void*, int64_t);
    using FnPerfHintCloseSession = void (*)(void*);

    static int32_t detectApiLevel() noexcept {
        char sdkBuf[PROP_VALUE_MAX] = {0};
        if (__system_property_get("ro.build.version.sdk", sdkBuf) > 0) {
            const int parsed = std::atoi(sdkBuf);
            if (parsed > 0) {
                return parsed;
            }
        }
        return __ANDROID_API__;
    }

    void loadPlatformSymbols() noexcept {
        libAndroidHandle_ = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (libAndroidHandle_ == nullptr) {
            return;
        }

        fnThermalAcquire_ = reinterpret_cast<FnThermalAcquire>(
            dlsym(libAndroidHandle_, "AThermal_acquireManager")
        );
        fnThermalRelease_ = reinterpret_cast<FnThermalRelease>(
            dlsym(libAndroidHandle_, "AThermal_releaseManager")
        );
        fnThermalGetStatus_ = reinterpret_cast<FnThermalGetStatus>(
            dlsym(libAndroidHandle_, "AThermal_getCurrentThermalStatus")
        );
        fnThermalGetHeadroom_ = reinterpret_cast<FnThermalGetHeadroom>(
            dlsym(libAndroidHandle_, "AThermal_getThermalHeadroom")
        );

        fnPerfHintGetManager_ = reinterpret_cast<FnPerfHintGetManager>(
            dlsym(libAndroidHandle_, "APerformanceHint_getManager")
        );
        fnPerfHintCreateSession_ = reinterpret_cast<FnPerfHintCreateSession>(
            dlsym(libAndroidHandle_, "APerformanceHint_createSession")
        );
        fnPerfHintGetPreferredRate_ = reinterpret_cast<FnPerfHintGetPreferredRate>(
            dlsym(libAndroidHandle_, "APerformanceHint_getPreferredUpdateRateNanos")
        );
        fnPerfHintUpdateTarget_ = reinterpret_cast<FnPerfHintUpdateTarget>(
            dlsym(libAndroidHandle_, "APerformanceHint_updateTargetWorkDuration")
        );
        fnPerfHintReportActual_ = reinterpret_cast<FnPerfHintReportActual>(
            dlsym(libAndroidHandle_, "APerformanceHint_reportActualWorkDuration")
        );
        fnPerfHintCloseSession_ = reinterpret_cast<FnPerfHintCloseSession>(
            dlsym(libAndroidHandle_, "APerformanceHint_closeSession")
        );
    }

    void stopAdpfSessionLocked() noexcept {
        adpfActive_.store(false, std::memory_order_release);
        if (perfHintSession_ != nullptr && fnPerfHintCloseSession_ != nullptr) {
            fnPerfHintCloseSession_(perfHintSession_);
            perfHintSession_ = nullptr;
        }
    }

    [[nodiscard]] ThermalSnapshot readThermalSnapshot() const noexcept {
        ThermalSnapshot out{};
        if (thermalManager_ == nullptr || fnThermalGetStatus_ == nullptr) {
            return out;
        }
        out.status = fnThermalGetStatus_(thermalManager_);
        out.supported = (out.status != kThermalStatusUnsupported);

        if (apiLevel_ >= 31 && fnThermalGetHeadroom_ != nullptr) {
            const float headroom = fnThermalGetHeadroom_(thermalManager_, 5);
            if (!std::isnan(headroom) && std::isfinite(headroom) && headroom >= 0.0f) {
                out.headroomForecast5s = headroom;
            }
        }
        return out;
    }

    [[nodiscard]] static MemorySnapshot readProcMemInfo() noexcept {
        MemorySnapshot mem{};
        std::ifstream file("/proc/meminfo");
        if (!file.is_open()) {
            return mem;
        }

        uint64_t totalKb = 0;
        uint64_t availableKb = 0;
        uint64_t freeKb = 0;
        uint64_t buffersKb = 0;
        uint64_t cachedKb = 0;

        std::string line;
        while (std::getline(file, line)) {
            std::istringstream iss(line);
            std::string key;
            uint64_t valueKb = 0;
            iss >> key >> valueKb;
            if (key == "MemTotal:") {
                totalKb = valueKb;
            } else if (key == "MemAvailable:") {
                availableKb = valueKb;
            } else if (key == "MemFree:") {
                freeKb = valueKb;
            } else if (key == "Buffers:") {
                buffersKb = valueKb;
            } else if (key == "Cached:") {
                cachedKb = valueKb;
            }
        }

        if (totalKb == 0) {
            return mem;
        }
        if (availableKb == 0) {
            availableKb = freeKb + buffersKb + cachedKb;
        }

        mem.totalMb = static_cast<double>(totalKb) / 1024.0;
        mem.availableMb = static_cast<double>(availableKb) / 1024.0;
        const double usedKb = totalKb > availableKb ? static_cast<double>(totalKb - availableKb) : 0.0;
        mem.usagePercent = (usedKb / static_cast<double>(totalKb)) * 100.0;
        mem.valid = true;
        return mem;
    }

    [[nodiscard]] FrameStatsSnapshot computeFrameStats() const noexcept {
        FrameStatsSnapshot stats{};
        stats.totalFrames = frameRing_.countTotal();

        std::array<double, 128> samples{};
        const size_t count = frameRing_.snapshot(samples);
        if (count == 0) {
            return stats;
        }

        double sum = 0.0;
        double deltaSum = 0.0;
        for (size_t i = 0; i < count; ++i) {
            sum += samples[i];
            if (i > 0) {
                deltaSum += std::abs(samples[i] - samples[i - 1]);
            }
        }
        stats.avgFrameTimeMs = sum / static_cast<double>(count);
        stats.jitterMs = count > 1 ? (deltaSum / static_cast<double>(count - 1)) : 0.0;

        std::sort(samples.begin(), samples.begin() + static_cast<std::ptrdiff_t>(count));
        const size_t p99Idx = std::min(count - 1, (count * 99) / 100);
        stats.p99FrameTimeMs = samples[p99Idx];
        return stats;
    }

    [[nodiscard]] NetworkStatsSnapshot computeNetworkStats() const noexcept {
        NetworkStatsSnapshot stats{};
        stats.totalRxBytes = totalRxBytes_.load(std::memory_order_relaxed);
        stats.totalTxBytes = totalTxBytes_.load(std::memory_order_relaxed);
        stats.jitterMs = smoothedNetJitterMs_.load(std::memory_order_relaxed);

        std::array<double, 64> samples{};
        const size_t count = netRttRing_.snapshot(samples);
        if (count == 0) {
            return stats;
        }
        double sum = 0.0;
        for (size_t i = 0; i < count; ++i) {
            sum += samples[i];
        }
        stats.avgRttMs = sum / static_cast<double>(count);
        return stats;
    }

    mutable std::mutex lifecycleMutex_{};
    std::atomic<bool> initialized_{false};
    std::atomic<bool> adpfActive_{false};
    int32_t apiLevel_{0};

    void* libAndroidHandle_{nullptr};
    void* thermalManager_{nullptr};
    void* perfHintManager_{nullptr};
    void* perfHintSession_{nullptr};

    FnThermalAcquire fnThermalAcquire_{nullptr};
    FnThermalRelease fnThermalRelease_{nullptr};
    FnThermalGetStatus fnThermalGetStatus_{nullptr};
    FnThermalGetHeadroom fnThermalGetHeadroom_{nullptr};

    FnPerfHintGetManager fnPerfHintGetManager_{nullptr};
    FnPerfHintCreateSession fnPerfHintCreateSession_{nullptr};
    FnPerfHintGetPreferredRate fnPerfHintGetPreferredRate_{nullptr};
    FnPerfHintUpdateTarget fnPerfHintUpdateTarget_{nullptr};
    FnPerfHintReportActual fnPerfHintReportActual_{nullptr};
    FnPerfHintCloseSession fnPerfHintCloseSession_{nullptr};

    AtomicSampleRing<128> frameRing_{};
    AtomicSampleRing<64> netRttRing_{};
    std::atomic<double> lastNetRttMs_{-1.0};
    std::atomic<double> smoothedNetJitterMs_{0.0};
    std::atomic<uint64_t> totalRxBytes_{0};
    std::atomic<uint64_t> totalTxBytes_{0};
};

} // namespace codm::native_engine
