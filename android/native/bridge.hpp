#pragma once

#include <jni.h>
#include <atomic>
#include <exception>
#include <stdexcept>
#include <string>

namespace video2 {

struct Cancellable {
    std::atomic_bool cancelled{false};
    void check() const {
        if (cancelled.load(std::memory_order_relaxed)) {
            throw std::runtime_error("On-device work cancelled");
        }
    }
    template<class T> static bool abort(void * data) {
        return static_cast<T *>(data)->cancelled.load(std::memory_order_relaxed);
    }
};

template<class T> T & session(jlong handle) {
    if (!handle) throw std::runtime_error("No on-device session");
    return *reinterpret_cast<T *>(handle);
}

inline std::string utf8(JNIEnv * env, jbyteArray input, jsize max) {
    const jsize size = env->GetArrayLength(input);
    if (size > max) throw std::runtime_error("On-device input is too long");
    std::string out(size, '\0');
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte *>(out.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read on-device input");
    if (out.find('\0') != std::string::npos) throw std::runtime_error("Invalid on-device input");
    return out;
}

inline jbyteArray bytes(JNIEnv * env, const std::string & str) {
    auto size = static_cast<jsize>(str.size());
    jbyteArray result = env->NewByteArray(size);
    if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte *>(str.data()));
    return result;
}

// Don't propagate a C++ exception through ART, or log model inputs/results.
template<class Fn> auto guarded(JNIEnv * env, Fn fn) -> decltype(fn()) {
    try {
        return fn();
    } catch (const std::exception & e) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
    } catch (...) {
        if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "On-device engine failed");
    }
    return decltype(fn()){};
}

} // namespace video2
