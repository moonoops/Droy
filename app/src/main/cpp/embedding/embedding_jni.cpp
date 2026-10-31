#include <jni.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"
#include "sentencepiece_processor.h"

namespace {
constexpr size_t kDimensions = 384;
constexpr size_t kMaxTokens = 512;
const OrtApi* api() {
    const OrtApi* result = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    if (!result) throw std::runtime_error("EMBEDDING_RUNTIME_VERSION");
    return result;
}
void check(OrtStatus* status) {
    if (status) {
        // ORT diagnostics can contain paths or input data; never return them to a caller.
        api()->ReleaseStatus(status);
        throw std::runtime_error("EMBEDDING_INFERENCE_FAILED");
    }
}
void fail(JNIEnv* env, const char* code) {
    if (!env->ExceptionCheck()) {
        jclass type = env->FindClass("java/lang/IllegalStateException");
        if (type) env->ThrowNew(type, code);
    }
}
std::string utf8(JNIEnv* env, jbyteArray bytes) {
    if (!bytes) throw std::runtime_error("EMBEDDING_INVALID_INPUT");
    jsize length = env->GetArrayLength(bytes);
    if (length < 1 || length > 16384 + 9) throw std::runtime_error("EMBEDDING_INVALID_INPUT");
    std::string result(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(bytes, 0, length, reinterpret_cast<jbyte*>(result.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("EMBEDDING_INVALID_INPUT");
    return result;
}
struct Engine {
    OrtEnv* environment = nullptr;
    OrtSession* session = nullptr;
    OrtMemoryInfo* memory = nullptr;
    sentencepiece::SentencePieceProcessor tokenizer;
    ~Engine() {
        const OrtApi* ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);
        if (!ort) return;
        if (session) ort->ReleaseSession(session);
        if (memory) ort->ReleaseMemoryInfo(memory);
        if (environment) ort->ReleaseEnv(environment);
    }
    std::vector<int64_t> tokens(const std::string& text) {
        std::vector<int64_t> ids;
        ids.reserve(kMaxTokens);
        ids.push_back(0); // XLM-R BOS, SentencePiece ids are shifted by fairseq_offset=1.
        // Match the official slow XLMRobertaTokenizer's added-token splitting.
        const std::pair<std::string, int64_t> specials[] = {{"<s>", 0}, {"<pad>", 1}, {"</s>", 2}, {"<unk>", 3}, {"<mask>", 250001}};
        size_t offset = 0;
        while (offset < text.size() && ids.size() < kMaxTokens - 1) {
            size_t next = std::string::npos;
            const std::pair<std::string, int64_t>* selected = nullptr;
            for (const auto& special : specials) {
                size_t found = text.find(special.first, offset);
                if (found < next) { next = found; selected = &special; }
            }
            std::vector<int> pieces;
            if (!tokenizer.Encode(text.substr(offset, next == std::string::npos ? next : next - offset), &pieces).ok())
                throw std::runtime_error("EMBEDDING_TOKENIZER_FAILED");
            for (int piece : pieces) {
                if (ids.size() >= kMaxTokens - 1) break;
                ids.push_back(piece == tokenizer.unk_id() ? 3 : piece + 1);
            }
            if (!selected) break;
            if (ids.size() < kMaxTokens - 1) ids.push_back(selected->second);
            offset = next + selected->first.size();
        }
        ids.push_back(2);
        return ids;
    }
    std::vector<float> embed(const std::string& text) {
        auto ids = tokens(text);
        std::vector<int64_t> mask(ids.size(), 1);
        std::vector<int64_t> segments(ids.size(), 0);
        int64_t shape[] = {1, static_cast<int64_t>(ids.size())};
        auto releaseValue = [](OrtValue* value) { if (value) api()->ReleaseValue(value); };
        std::unique_ptr<OrtValue, decltype(releaseValue)> input(nullptr, releaseValue), attention(nullptr, releaseValue), tokenTypes(nullptr, releaseValue), output(nullptr, releaseValue);
        OrtValue* raw = nullptr;
        check(api()->CreateTensorWithDataAsOrtValue(memory, ids.data(), ids.size() * sizeof(int64_t), shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &raw)); input.reset(raw);
        raw = nullptr;
        check(api()->CreateTensorWithDataAsOrtValue(memory, mask.data(), mask.size() * sizeof(int64_t), shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &raw)); attention.reset(raw);
        raw = nullptr;
        check(api()->CreateTensorWithDataAsOrtValue(memory, segments.data(), segments.size() * sizeof(int64_t), shape, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &raw)); tokenTypes.reset(raw);
        const char* names[] = {"input_ids", "attention_mask", "token_type_ids"};
        const OrtValue* values[] = {input.get(), attention.get(), tokenTypes.get()};
        const char* outputs[] = {"last_hidden_state"};
        raw = nullptr;
        check(api()->Run(session, nullptr, names, values, 3, outputs, 1, &raw)); output.reset(raw);
        OrtTensorTypeAndShapeInfo* tensorInfo = nullptr;
        check(api()->GetTensorTypeAndShape(output.get(), &tensorInfo));
        auto releaseInfo = [](OrtTensorTypeAndShapeInfo* info) { api()->ReleaseTensorTypeAndShapeInfo(info); };
        std::unique_ptr<OrtTensorTypeAndShapeInfo, decltype(releaseInfo)> info(tensorInfo, releaseInfo);
        ONNXTensorElementDataType type; size_t count; size_t rank;
        check(api()->GetTensorElementType(info.get(), &type));
        check(api()->GetTensorShapeElementCount(info.get(), &count));
        check(api()->GetDimensionsCount(info.get(), &rank));
        int64_t actual[3] = {};
        if (rank != 3) throw std::runtime_error("EMBEDDING_OUTPUT_SHAPE");
        check(api()->GetDimensions(info.get(), actual, 3));
        if (type != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT || actual[0] != 1 || actual[1] != static_cast<int64_t>(ids.size()) || actual[2] != kDimensions || count != ids.size() * kDimensions)
            throw std::runtime_error("EMBEDDING_OUTPUT_SHAPE");
        float* data = nullptr;
        check(api()->GetTensorMutableData(output.get(), reinterpret_cast<void**>(&data)));
        std::vector<float> pooled(kDimensions, 0);
        double norm = 0;
        for (size_t dim = 0; dim < kDimensions; ++dim) {
            double total = 0;
            for (size_t token = 0; token < ids.size(); ++token) total += data[token * kDimensions + dim];
            pooled[dim] = static_cast<float>(total / ids.size());
            if (!std::isfinite(pooled[dim])) throw std::runtime_error("EMBEDDING_OUTPUT_INVALID");
            norm += static_cast<double>(pooled[dim]) * pooled[dim];
        }
        if (!std::isfinite(norm) || norm <= 0) throw std::runtime_error("EMBEDDING_OUTPUT_INVALID");
        double divisor = std::sqrt(norm);
        for (float& value : pooled) value = static_cast<float>(value / divisor);
        return pooled;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_io_droyagent_app_embedding_NativeEmbeddingEngine_nativeLoad(JNIEnv* env, jclass, jbyteArray directory) {
    try {
        std::string path = utf8(env, directory);
        auto engine = std::make_unique<Engine>();
        if (!engine->tokenizer.Load(path + "/sentencepiece.bpe.model").ok()) throw std::runtime_error("EMBEDDING_TOKENIZER_FAILED");
        check(api()->CreateEnv(ORT_LOGGING_LEVEL_FATAL, "DroyEmbedding", &engine->environment));
        OrtSessionOptions* raw = nullptr;
        check(api()->CreateSessionOptions(&raw));
        auto release = [](OrtSessionOptions* options) { api()->ReleaseSessionOptions(options); };
        std::unique_ptr<OrtSessionOptions, decltype(release)> options(raw, release);
        check(api()->SetIntraOpNumThreads(options.get(), 2));
        check(api()->SetInterOpNumThreads(options.get(), 1));
        check(api()->SetSessionGraphOptimizationLevel(options.get(), ORT_ENABLE_ALL));
        check(api()->CreateSession(engine->environment, (path + "/model.onnx").c_str(), options.get(), &engine->session));
        check(api()->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &engine->memory));
        return reinterpret_cast<jlong>(engine.release());
    } catch (const std::exception& error) { fail(env, error.what()); }
      catch (...) { fail(env, "EMBEDDING_LOAD_FAILED"); }
    return 0;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_io_droyagent_app_embedding_NativeEmbeddingEngine_nativeEmbed(JNIEnv* env, jclass, jlong handle, jbyteArray text) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine) throw std::runtime_error("EMBEDDING_NOT_READY");
        auto vector = engine->embed(utf8(env, text));
        jfloatArray result = env->NewFloatArray(static_cast<jsize>(vector.size()));
        if (result) env->SetFloatArrayRegion(result, 0, static_cast<jsize>(vector.size()), vector.data());
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); }
      catch (...) { fail(env, "EMBEDDING_INFERENCE_FAILED"); }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_io_droyagent_app_embedding_NativeEmbeddingEngine_nativeTokens(JNIEnv* env, jclass, jlong handle, jbyteArray text) {
    try {
        auto* engine = reinterpret_cast<Engine*>(handle);
        if (!engine) throw std::runtime_error("EMBEDDING_NOT_READY");
        auto ids = engine->tokens(utf8(env, text));
        std::vector<jint> integers(ids.begin(), ids.end());
        jintArray result = env->NewIntArray(static_cast<jsize>(integers.size()));
        if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(integers.size()), integers.data());
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); }
      catch (...) { fail(env, "EMBEDDING_TOKENIZER_FAILED"); }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_io_droyagent_app_embedding_NativeEmbeddingEngine_nativeClose(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Engine*>(handle);
}
