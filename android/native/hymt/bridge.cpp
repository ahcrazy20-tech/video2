#include "bridge.hpp"
#include "llama.h"

#include <algorithm>
#include <memory>
#include <vector>

#if defined(__ANDROID__) && defined(__aarch64__) && !defined(VIDEO2_HYMT_DOTPROD)
#include <asm/hwcap.h>
#include <sys/auxv.h>
#endif

#ifdef VIDEO2_HYMT_DOTPROD
#define HYMT_JNI(name) Java_com_ahcrazy_video2_data_offline_HymtDotprodJni_##name
#else
#define HYMT_JNI(name) Java_com_ahcrazy_video2_data_offline_HymtJni_##name
#endif

namespace {
constexpr int context_size = 1536; // Do not allocate the model's training context (262K tokens).
struct Hymt : video2::Cancellable {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> engine{nullptr, llama_free};
    std::vector<llama_token> prefix, stops;
    llama_token assistant = -1;
    float top_p = 0.6f;
};
void quiet(ggml_log_level, const char *, void *) {}

std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & input, bool special) {
    std::vector<llama_token> tokens(input.size() + 1);
    const int count = llama_tokenize(vocab, input.data(), static_cast<int32_t>(input.size()),
                                    tokens.data(), static_cast<int32_t>(tokens.size()), false, special);
    if (count <= 0) throw std::runtime_error("Hy-MT2 could not tokenize the subtitle text");
    tokens.resize(count);
    return tokens;
}
llama_token special(const llama_vocab * vocab, const char * text, llama_token expected) {
    const auto t = tokenize(vocab, text, true);
    if (t.size() != 1 || t.front() != expected) throw std::runtime_error("Unexpected Hy-MT2 chat template tokens");
    return t.front();
}

void set_template(Hymt & s, bool compact) {
    const auto * v = llama_model_get_vocab(s.model.get());
    if (compact) {
        // 1.8B uses the HunYuan Dense tokenizer and a different single-user wrapper.
        s.prefix = {special(v, "<｜hy_begin▁of▁sentence｜>", 120000), special(v, "<｜hy_User｜>", 120006)};
        s.assistant = special(v, "<｜hy_Assistant｜>", 120007);
        s.stops = {special(v, "<｜hy_place▁holder▁no▁2｜>", 120020)};
        s.top_p = 0.8f;
    } else {
        s.prefix = {special(v, "<|startoftext|>", 127958)};
        s.assistant = special(v, "<|extra_0|>", 127962);
        // The 7B GGUF declares EOS=3 (an ordinary token). Honor the publisher's generation config.
        s.stops = {special(v, "<|eos|>", 127960), special(v, "<|extra_5|>", 127967)};
        s.top_p = 0.6f;
    }
}
} // namespace

#ifndef VIDEO2_HYMT_DOTPROD
extern "C" JNIEXPORT jboolean JNICALL HYMT_JNI(supportsDotprod)(JNIEnv *, jobject) {
#if defined(__ANDROID__) && defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}
#endif

extern "C" JNIEXPORT jlong JNICALL HYMT_JNI(create)(JNIEnv * env, jobject) {
    return video2::guarded(env, [] { return reinterpret_cast<jlong>(new Hymt()); });
}

extern "C" JNIEXPORT void JNICALL HYMT_JNI(cancel)(JNIEnv *, jobject, jlong handle) {
    reinterpret_cast<Hymt *>(handle)->cancelled.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL HYMT_JNI(destroy)(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Hymt *>(handle);
}

extern "C" JNIEXPORT void JNICALL HYMT_JNI(load)(JNIEnv * env, jobject, jlong handle, jbyteArray path, jint threads, jboolean compact) {
    video2::guarded(env, [&] {
        auto & s = video2::session<Hymt>(handle);
        s.check();
        llama_log_set(quiet, nullptr);
        llama_backend_init();
        auto model = llama_model_default_params();
        model.n_gpu_layers = 0;
        model.load_mode = LLAMA_LOAD_MODE_MMAP;
        model.use_extra_bufts = false; // Don't make a second in-memory repacked copy of the weights.
        model.progress_callback = [](float, void * d) { return !video2::Cancellable::abort<Hymt>(d); };
        model.progress_callback_user_data = &s;
        s.model.reset(llama_model_load_from_file(video2::utf8(env, path, 4096).c_str(), model));
        s.check();
        if (!s.model) throw std::runtime_error("Hy-MT2 could not load its model");
        set_template(s, compact);
        auto p = llama_context_default_params();
        p.n_ctx = context_size;
        p.n_batch = 192;
        p.n_ubatch = 192;
        p.n_outputs_max = 1;
        p.n_outputs_max_per_seq = 1;
        p.n_threads = threads;
        p.n_threads_batch = threads;
        p.no_perf = true;
        p.abort_callback = video2::Cancellable::abort<Hymt>;
        p.abort_callback_data = &s;
        s.engine.reset(llama_init_from_model(s.model.get(), p));
        s.check();
        if (!s.engine) throw std::runtime_error("Hy-MT2 could not allocate its context");
        return true;
    });
}

// Ordered by the one MT worker between calls; raise its CPU budget after Whisper exits.
extern "C" JNIEXPORT void JNICALL HYMT_JNI(setThreads)(JNIEnv * env, jobject, jlong handle, jint threads) {
    video2::guarded(env, [&] {
        auto & s = video2::session<Hymt>(handle);
        s.check();
        if (!s.engine) throw std::runtime_error("Hy-MT2 model is not loaded");
        const int n = std::clamp<int>(threads, 1, 6);
        llama_set_n_threads(s.engine.get(), n, n);
        return true;
    });
}

extern "C" JNIEXPORT jbyteArray JNICALL HYMT_JNI(translate)(
        JNIEnv * env, jobject, jlong handle, jbyteArray input, jint seed) {
    return video2::guarded(env, [&]() -> jbyteArray {
        auto & s = video2::session<Hymt>(handle);
        s.check();
        if (!s.engine) throw std::runtime_error("Hy-MT2 model is not loaded");
        const auto * vocab = llama_model_get_vocab(s.model.get());
        // The transcript is ordinary text, even if it happens to contain a control-token spelling.
        auto tokens = tokenize(vocab, video2::utf8(env, input, 8192), false);
        tokens.insert(tokens.begin(), s.prefix.begin(), s.prefix.end());
        tokens.push_back(s.assistant);
        const int max_output = std::min(768, context_size - static_cast<int>(tokens.size()) - 1);
        // Null means the caller must retry with a smaller batch. Never return a truncated translation.
        if (max_output < 120) return nullptr;

        llama_memory_clear(llama_get_memory(s.engine.get()), false);
        auto sp = llama_sampler_chain_default_params();
        sp.no_perf = true;
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(llama_sampler_chain_init(sp), llama_sampler_free);
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.05f, 0.0f, 0.0f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(20));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(s.top_p, 1));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(0.7f));
        llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(static_cast<uint32_t>(seed)));

        std::unique_ptr<llama_batch_ext, decltype(&llama_batch_ext_free)> batch(llama_batch_ext_init(s.engine.get()), llama_batch_ext_free);
        if (!batch) throw std::runtime_error("Hy-MT2 could not create a token batch");
        llama_pos position = 0;
        auto feed = [&](const llama_token * data, int count, bool want_logits) {
            s.check();
            llama_batch_ext_clear(batch.get());
            for (int i = 0; i < count; ++i) {
                const int row = llama_batch_ext_add_token(batch.get(), 0, data[i]);
                const llama_pos pos[] = { position++, 0, 0, 0 };
                if (row < 0 || !llama_batch_ext_set_pos(batch.get(), row, pos) || !llama_batch_ext_set_output_logits(batch.get(), row, want_logits && i == count - 1)) {
                    throw std::runtime_error("Hy-MT2 could not queue subtitle tokens");
                }
            }
            const int result = llama_process(s.engine.get(), LLAMA_PROCESS_TYPE_DECODE, batch.get());
            s.check();
            if (result != 0) throw std::runtime_error("Hy-MT2 could not decode subtitle tokens");
        };
        for (size_t i = 0; i < tokens.size();) {
            const int count = static_cast<int>(std::min<size_t>(llama_n_batch(s.engine.get()), tokens.size() - i));
            feed(tokens.data() + i, count, i + count == tokens.size());
            i += count;
        }
        std::string text;
        for (int i = 0; i < max_output; ++i) {
            s.check();
            const auto token = llama_sampler_sample(sampler.get(), s.engine.get(), -1);
            if (std::find(s.stops.begin(), s.stops.end(), token) != s.stops.end()) return video2::bytes(env, text);
            char piece[512];
            int size = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (size < 0) return nullptr;
            if (size > 0) text.append(piece, size);
            feed(&token, 1, true);
        }
        return nullptr;
    });
}
