#include <jni.h>
#include <memory>
#include <stdexcept>
#include <string>
#include "qwen3_tts_c.h"

namespace {
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, message);
    }
}
struct UtfString {
    JNIEnv* env; jstring value; const char* bytes;
    UtfString(JNIEnv* e, jstring v) : env(e), value(v), bytes(e->GetStringUTFChars(v, nullptr)) {}
    ~UtfString() { if (bytes) env->ReleaseStringUTFChars(value, bytes); }
};
struct Callback {
    JNIEnv* env; jobject target; jmethodID method;
    bool stopped = false;
    jthrowable failure = nullptr;
};
int32_t on_audio(const qwen3_tts_audio_chunk_t* chunk, void* opaque) {
    auto& state = *static_cast<Callback*>(opaque);
    if (state.stopped) return 0;
    if (chunk->sample_rate != 24000 || chunk->n_samples < 0) {
        fail(state.env, "Qwen3-TTS returned an unsupported audio format");
    } else if (chunk->n_samples > 0) {
        jfloatArray samples = state.env->NewFloatArray(chunk->n_samples);
        if (samples) {
            state.env->SetFloatArrayRegion(samples, 0, chunk->n_samples, chunk->samples);
            if (!state.env->ExceptionCheck())
                state.stopped = state.env->CallBooleanMethod(state.target, state.method, samples) != JNI_TRUE;
            state.env->DeleteLocalRef(samples);
        }
    }
    if (state.env->ExceptionCheck()) {
        // The pipeline must unwind before Java sees the callback failure.
        state.failure = state.env->ExceptionOccurred();
        state.env->ExceptionClear();
        state.stopped = true;
    }
    return state.stopped ? 0 : 1;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_droyagent_app_QwenTts_nativeLoad(JNIEnv* env, jclass, jstring directory) {
    try {
        UtfString path(env, directory);
        if (!path.bytes) return 0;
        qwen3_tts_set_backend_preference(QWEN3_TTS_BACKEND_CPU);
        qwen3_tts_set_cpu_threads(4);
        std::unique_ptr<qwen3_tts_context_t, decltype(&qwen3_tts_free)> ctx(qwen3_tts_init(), qwen3_tts_free);
        if (!ctx || !qwen3_tts_load_models_with_name(ctx.get(), path.bytes, "qwen-talker-0.6b-customvoice-Q4_K_M.gguf"))
            throw std::runtime_error("Qwen3-TTS model could not be loaded");
        auto capabilities = qwen3_tts_get_model_capabilities(ctx.get());
        if (!capabilities.supports_named_speakers)
            throw std::runtime_error("Qwen3-TTS CustomVoice model is required");
        return reinterpret_cast<jlong>(ctx.release());
    } catch (const std::exception& error) { fail(env, error.what()); }
      catch (...) { fail(env, "Qwen3-TTS model initialization failed"); }
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_io_droyagent_app_QwenTts_nativeGenerate(JNIEnv* env, jclass, jlong handle, jbyteArray input,
                                          jstring speaker, jobject callback) {
    try {
        auto* ctx = reinterpret_cast<qwen3_tts_context_t*>(handle);
        if (!ctx) throw std::runtime_error("Qwen3-TTS context is closed");
        jsize length = env->GetArrayLength(input);
        std::string text(static_cast<size_t>(length), '\0');
        env->GetByteArrayRegion(input, 0, length, reinterpret_cast<jbyte*>(text.data()));
        UtfString voice(env, speaker);
        if (env->ExceptionCheck() || !voice.bytes) return;
        jclass type = env->GetObjectClass(callback);
        if (!type) return;
        jmethodID method = env->GetMethodID(type, "accept", "([F)Z");
        env->DeleteLocalRef(type);
        if (!method) return;
        Callback state{env, callback, method};
        qwen3_tts_streaming_params_t params{};
        params.generation.max_audio_tokens = 1024;
        params.generation.temperature = .9f;
        params.generation.top_p = 1;
        params.generation.top_k = 50;
        params.generation.n_threads = 4;
        params.generation.repetition_penalty = 1.05f;
        params.generation.language_id = 2055;
        params.generation.speaker = voice.bytes;
        params.chunk_sec = .32f;
        params.left_context_sec = 2;
        params.collect_audio = 0;
        auto result = qwen3_tts_synthesize_streaming(ctx, text.c_str(), params, on_audio, &state);
        std::string error = result.error_msg ? result.error_msg : "Qwen3-TTS synthesis failed";
        bool success = result.success != 0;
        qwen3_tts_free_result(result);
        if (state.failure) { env->Throw(state.failure); env->DeleteLocalRef(state.failure); }
        else if (!success && !state.stopped) fail(env, error.c_str());
    } catch (const std::exception& error) { fail(env, error.what()); }
      catch (...) { fail(env, "Qwen3-TTS synthesis failed"); }
}

extern "C" JNIEXPORT void JNICALL
Java_io_droyagent_app_QwenTts_nativeClose(JNIEnv* env, jclass, jlong handle) {
    try { qwen3_tts_free(reinterpret_cast<qwen3_tts_context_t*>(handle)); }
    catch (const std::exception& error) { fail(env, error.what()); }
    catch (...) { fail(env, "Qwen3-TTS release failed"); }
}
