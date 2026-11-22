#include "telemetry_engine.hpp"

#include <android/log.h>
#include <jni.h>
#include <unistd.h>
#include <vector>

namespace {

constexpr const char* kLogTag = "CodmNativeBooster";

codm::native_engine::TelemetryEngine& getEngine() {
    static codm::native_engine::TelemetryEngine engine;
    return engine;
}

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_bridge_NativeBridge_nativeInitEngine(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    const bool ok = getEngine().initialize();
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "TelemetryEngine initialized: %s",
        ok ? "true" : "false"
    );
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_bridge_NativeBridge_nativeShutdownEngine(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    getEngine().shutdown();
}

JNIEXPORT jboolean JNICALL
Java_com_example_bridge_NativeBridge_nativeStartAdpfSession(
    JNIEnv* env,
    jobject /* thiz */,
    jint targetFps,
    jintArray threadIds
) {
    std::vector<int32_t> tids;
    if (threadIds != nullptr) {
        const jsize len = env->GetArrayLength(threadIds);
        if (len > 0) {
            jint* elements = env->GetIntArrayElements(threadIds, nullptr);
            if (elements != nullptr) {
                tids.reserve(static_cast<size_t>(len));
                for (jsize i = 0; i < len; ++i) {
                    if (elements[i] > 0) {
                        tids.push_back(static_cast<int32_t>(elements[i]));
                    }
                }
                env->ReleaseIntArrayElements(threadIds, elements, JNI_ABORT);
            }
        }
    }

    if (tids.empty()) {
        tids.push_back(static_cast<int32_t>(gettid()));
    }

    const bool started = getEngine().startAdpfSession(static_cast<int32_t>(targetFps), tids);
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "ADPF session start (%d FPS, %zu threads): %s",
        static_cast<int>(targetFps),
        tids.size(),
        started ? "ACTIVE" : "UNAVAILABLE"
    );
    return started ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_bridge_NativeBridge_nativeUpdateAdpfTargetFps(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jint targetFps
) {
    return getEngine().updateAdpfTargetFps(static_cast<int32_t>(targetFps)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_example_bridge_NativeBridge_nativeReportAdpfWorkDuration(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong actualDurationNanos
) {
    return getEngine().reportAdpfWorkDuration(static_cast<int64_t>(actualDurationNanos))
        ? JNI_TRUE
        : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_bridge_NativeBridge_nativeStopAdpfSession(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    getEngine().stopAdpfSession();
}

JNIEXPORT void JNICALL
Java_com_example_bridge_NativeBridge_nativeRecordFrameTimeNanos(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jlong durationNanos
) {
    getEngine().recordFrameDurationNanos(static_cast<int64_t>(durationNanos));
}

JNIEXPORT void JNICALL
Java_com_example_bridge_NativeBridge_nativeRecordNetworkSample(
    JNIEnv* /* env */,
    jobject /* thiz */,
    jdouble rttMs,
    jlong rxBytes,
    jlong txBytes
) {
    getEngine().recordNetworkSample(
        static_cast<double>(rttMs),
        rxBytes > 0 ? static_cast<uint64_t>(rxBytes) : 0ULL,
        txBytes > 0 ? static_cast<uint64_t>(txBytes) : 0ULL
    );
}

JNIEXPORT jint JNICALL
Java_com_example_bridge_NativeBridge_nativeGetCurrentTid(
    JNIEnv* /* env */,
    jobject /* thiz */
) {
    return static_cast<jint>(gettid());
}

JNIEXPORT jdoubleArray JNICALL
Java_com_example_bridge_NativeBridge_nativeCollectTelemetry(
    JNIEnv* env,
    jobject /* thiz */
) {
    const auto snap = getEngine().collectSnapshot();

    constexpr jsize kFieldCount = 16;
    jdouble values[kFieldCount] = {
        static_cast<jdouble>(snap.apiLevel),
        static_cast<jdouble>(snap.thermal.status),
        static_cast<jdouble>(snap.thermal.headroomForecast5s),
        snap.memory.valid ? snap.memory.totalMb : -1.0,
        snap.memory.valid ? snap.memory.availableMb : -1.0,
        snap.memory.valid ? snap.memory.usagePercent : -1.0,
        snap.adpfSupported ? 1.0 : 0.0,
        snap.adpfSessionActive ? 1.0 : 0.0,
        static_cast<jdouble>(snap.adpfPreferredRateNanos),
        snap.frameStats.avgFrameTimeMs,
        snap.frameStats.jitterMs,
        snap.frameStats.p99FrameTimeMs,
        snap.networkStats.avgRttMs,
        snap.networkStats.jitterMs,
        static_cast<jdouble>(snap.onlineCpuCores),
        static_cast<jdouble>(gettid())
    };

    jdoubleArray out = env->NewDoubleArray(kFieldCount);
    if (out != nullptr) {
        env->SetDoubleArrayRegion(out, 0, kFieldCount, values);
    }
    return out;
}

} // extern "C"
