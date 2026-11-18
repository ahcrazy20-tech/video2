// Included after the pinned whisper implementation, only in the Vulkan JNI build.
// use_gpu expresses a preference; this verifies which backend actually initialized.
#include "ggml-vulkan.h"

extern "C" bool video2_whisper_uses_vulkan(const whisper_context * ctx) {
    return ctx && ctx->state && !ctx->state->backends.empty() && ggml_backend_is_vk(ctx->state->backends.front());
}
