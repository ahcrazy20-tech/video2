#include "bridge.hpp"
#include "whisper.h"

#include <algorithm>
#include <memory>
#include <vector>

#if defined(__ANDROID__) && defined(__aarch64__) && !defined(VIDEO2_WHISPER_FP16) && !defined(VIDEO2_WHISPER_VULKAN)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

#if defined(VIDEO2_WHISPER_VULKAN)
#define WHISPER_JNI(name) Java_com_ahcrazy_video2_data_offline_WhisperVulkanJni_##name
extern "C" bool video2_whisper_uses_vulkan(const whisper_context * ctx);
#elif defined(VIDEO2_WHISPER_FP16)
#define WHISPER_JNI(name) Java_com_ahcrazy_video2_data_offline_WhisperFp16Jni_##name
#else
#define WHISPER_JNI(name) Java_com_ahcrazy_video2_data_offline_WhisperJni_##name
#endif

namespace {
struct Whisper : video2::Cancellable {
    std::unique_ptr<whisper_context, decltype(&whisper_free)> engine{nullptr, whisper_free};
    int threads = 4;
};
void quiet(ggml_log_level, const char *, void *) {}

// whisper_full invokes the progress callback on the calling thread, not a ggml worker.
struct DecodeProgress {
    JNIEnv * env;
    jobject callback;
    jmethodID update;
    static void report(whisper_context *, whisper_state *, int percent, void * opaque) {
        auto & p = *static_cast<DecodeProgress *>(opaque);
        if (!p.env->ExceptionCheck()) p.env->CallVoidMethod(p.callback, p.update, static_cast<jint>(percent));
    }
};
} // namespace

#if !defined(VIDEO2_WHISPER_FP16) && !defined(VIDEO2_WHISPER_VULKAN)
extern "C" JNIEXPORT jboolean JNICALL WHISPER_JNI(supportsFp16)(JNIEnv *, jobject) {
#if defined(__ANDROID__) && defined(__aarch64__)
    constexpr auto required = HWCAP_FPHP | HWCAP_ASIMDHP;
    return (getauxval(AT_HWCAP) & required) == required ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
#endif

extern "C" JNIEXPORT jlong JNICALL WHISPER_JNI(create)(JNIEnv * env, jobject) {
    return video2::guarded(env, [] { return reinterpret_cast<jlong>(new Whisper()); });
}

extern "C" JNIEXPORT void JNICALL WHISPER_JNI(cancel)(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Whisper *>(handle)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL WHISPER_JNI(destroy)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Whisper *>(handle);
}

extern "C" JNIEXPORT void JNICALL WHISPER_JNI(load)(JNIEnv * env, jobject, jlong handle, jbyteArray path, jint threads) {
    video2::guarded(env, [&] {
        auto & s = video2::session<Whisper>(handle);
        s.check();
        s.threads = threads;
        whisper_log_set(quiet, nullptr);
        auto params = whisper_context_default_params();
#ifdef VIDEO2_WHISPER_VULKAN
        params.use_gpu = true;
#else
        params.use_gpu = false;
#endif
        params.flash_attn = true;
        s.engine.reset(whisper_init_from_file_with_params(video2::utf8(env, path, 4096).c_str(), params));
        s.check();
        if (!s.engine) throw std::runtime_error("Whisper could not load its model");
#ifdef VIDEO2_WHISPER_VULKAN
        if (!video2_whisper_uses_vulkan(s.engine.get())) throw std::runtime_error("Whisper did not initialize a Vulkan device");
#endif
        return true;
    });
}

extern "C" JNIEXPORT jobjectArray JNICALL WHISPER_JNI(decode)(
        JNIEnv * env, jobject, jlong handle, jshortArray pcm, jbyteArray source, jbyteArray previous,
        jboolean fast, jobject progress) {
    return video2::guarded(env, [&]() -> jobjectArray {
        auto & s = video2::session<Whisper>(handle);
        s.check();
        if (!s.engine) throw std::runtime_error("Whisper model is not loaded");
        const jsize count = env->GetArrayLength(pcm);
        if (count <= 0 || count > 75 * WHISPER_SAMPLE_RATE) throw std::runtime_error("Invalid Whisper PCM length");
        std::vector<jshort> shorts(count);
        env->GetShortArrayRegion(pcm, 0, count, shorts.data());
        if (env->ExceptionCheck()) return nullptr;
        // whisper_full needs enough samples for its initial seek loop even at the end of a video.
        std::vector<float> samples(std::max(count, WHISPER_SAMPLE_RATE));
        for (jsize i = 0; i < count; ++i) samples[i] = shorts[i] / 32768.0f;
        const auto lang = video2::utf8(env, source, 16);
        const auto prompt = video2::utf8(env, previous, 1536);
        auto p = whisper_full_default_params(WHISPER_SAMPLING_BEAM_SEARCH);
        p.n_threads = s.threads;
        p.translate = false;
        p.no_context = true; // Reset at window start; Whisper still has context within the window.
        p.language = lang.c_str();
        p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();
        p.print_progress = false;
        p.print_timestamps = false;
        p.suppress_nst = true;
        p.beam_search.beam_size = fast ? 2 : 5;
        p.greedy.best_of = 5; // Keep the existing low-confidence temperature fallback in both modes.
        DecodeProgress update{env, progress, nullptr};
        if (progress) {
            const auto cls = env->GetObjectClass(progress);
            if (!cls) return nullptr;
            update.update = env->GetMethodID(cls, "update", "(I)V");
            env->DeleteLocalRef(cls);
            if (!update.update) return nullptr;
            p.progress_callback = DecodeProgress::report;
            p.progress_callback_user_data = &update;
        }
        p.abort_callback = video2::Cancellable::abort<Whisper>;
        p.abort_callback_user_data = &s;
        const int result = whisper_full(s.engine.get(), p, samples.data(), static_cast<int>(samples.size()));
        if (env->ExceptionCheck()) return nullptr;
        s.check();
        if (result != 0) throw std::runtime_error("Whisper could not transcribe this audio window");

        const int32_t n = whisper_full_n_segments(s.engine.get());
        const char * detected = whisper_lang_str(whisper_full_lang_id(s.engine.get()));
        auto klass = env->FindClass("[B");
        if (!klass) return nullptr;
        auto output = env->NewObjectArray(n + 1, klass, nullptr);
        if (!output) return nullptr;
        auto put = [&](int i, const std::string & str) {
            auto part = video2::bytes(env, str);
            if (!part) return false;
            env->SetObjectArrayElement(output, i, part);
            env->DeleteLocalRef(part);
            return !env->ExceptionCheck();
        };
        if (!put(0, detected ? detected : "")) return nullptr;
        for (int32_t i = 0; i < n; ++i) {
            // Whisper positions are in centiseconds relative to this PCM window.
            const auto t0 = whisper_full_get_segment_t0(s.engine.get(), i);
            const auto t1 = whisper_full_get_segment_t1(s.engine.get(), i);
            if (!put(i + 1, std::to_string(t0) + '\t' + std::to_string(t1) + '\t' + whisper_full_get_segment_text(s.engine.get(), i))) return nullptr;
        }
        return output;
    });
}
