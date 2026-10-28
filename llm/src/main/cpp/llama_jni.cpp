// JNI bridge between Kotlin (dev.offlineflow.llm.LlamaEngine) and llama.cpp.
//
// The caller formats prompts itself (each model family has its own chat format).
// Each fixed prefix (system prompt + few-shot examples; one per style) is decoded once and
// its sequence state is snapshotted. Each request restores a snapshot and decodes only
// the new turn. A snapshot (rather than trimming the KV cache) also works for models
// whose memory can't be partially rewound: hybrid/recurrent (Qwen3.5) and sliding-window
// attention (Gemma 4).
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <string>
#include <vector>
#include "llama.h"

#define TAG "hearsay-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Llm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    struct Prefix { std::vector<uint8_t> state; int n_tokens; };
    std::vector<Prefix> prefixes;  // sequence 0 state right after each prefix
    int n_ctx = 0;
    int n_batch = 0;
};

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &s, bool add_special) {
    int n = -llama_tokenize(vocab, s.c_str(), (int32_t) s.size(), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> t(n);
    int got = llama_tokenize(vocab, s.c_str(), (int32_t) s.size(), t.data(), n, add_special, true);
    if (got < 0) return {};
    t.resize(got);
    return t;
}

bool decode_all(Llm &l, std::vector<llama_token> &toks) {
    for (size_t i = 0; i < toks.size(); i += l.n_batch) {
        int n = (int) std::min<size_t>(l.n_batch, toks.size() - i);
        if (llama_decode(l.ctx, llama_batch_get_one(toks.data() + i, n)) != 0) return false;
    }
    return true;
}

bool restore_prefix(Llm &l, const Llm::Prefix &p) {
    llama_memory_seq_rm(llama_get_memory(l.ctx), 0, -1, -1);
    return llama_state_seq_set_data(l.ctx, p.state.data(), p.state.size(), 0) > 0;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &out) {
    // Build via String(byte[], "UTF-8"): token pieces may not be valid modified UTF-8.
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    jclass cls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(cls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(cls);
    return result;
}

void free_llm(Llm *l) {
    if (l->sampler) llama_sampler_free(l->sampler);
    if (l->ctx) llama_free(l->ctx);
    if (l->model) llama_model_free(l->model);
    delete l;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeInit(JNIEnv *env, jclass, jstring jpath, jint threads) {
    static bool backend = false;
    if (!backend) { llama_backend_init(); backend = true; }

    auto *l = new Llm();
    std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    l->model = llama_model_load_from_file(path.c_str(), mp);
    if (!l->model) { LOGE("model load failed: %s", path.c_str()); free_llm(l); return 0; }
    l->vocab = llama_model_get_vocab(l->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_seq_max = 1;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    l->ctx = llama_init_from_model(l->model, cp);
    if (!l->ctx) { LOGE("context init failed"); free_llm(l); return 0; }
    l->n_ctx = (int) llama_n_ctx(l->ctx);
    l->n_batch = (int) cp.n_batch;

    l->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(l->sampler, llama_sampler_init_greedy());

    LOGI("llm loaded: threads=%d", threads);
    return reinterpret_cast<jlong>(l);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeAddPrefix(JNIEnv *env, jobject, jlong handle, jstring jprefix) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (!l) return -1;
    llama_memory_seq_rm(llama_get_memory(l->ctx), 0, -1, -1);
    auto toks = tokenize(l->vocab, jstr(env, jprefix), true);
    if (toks.empty() || (int) toks.size() >= l->n_ctx - 256 || !decode_all(*l, toks)) {
        LOGE("prefix decode failed (%zu tokens)", toks.size());
        return -1;
    }
    Llm::Prefix p;
    p.n_tokens = (int) toks.size();
    p.state.resize(llama_state_seq_get_size(l->ctx, 0));
    size_t n = llama_state_seq_get_data(l->ctx, p.state.data(), p.state.size(), 0);
    if (n == 0) { LOGE("snapshot failed"); return -1; }
    p.state.resize(n);
    l->prefixes.push_back(std::move(p));
    LOGI("prefix %zu: %d tokens, %zu bytes", l->prefixes.size() - 1, l->prefixes.back().n_tokens, n);
    return (jint) (l->prefixes.size() - 1);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeComplete(JNIEnv *env, jobject, jlong handle,
                                                     jint prefix, jstring jsuffix, jint max_tokens) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (!l || prefix < 0 || prefix >= (jint) l->prefixes.size()) return env->NewStringUTF("");
    const auto &pre = l->prefixes[prefix];
    if (!restore_prefix(*l, pre)) { LOGE("restore failed"); return env->NewStringUTF(""); }

    auto toks = tokenize(l->vocab, jstr(env, jsuffix), false);
    int pos = pre.n_tokens + (int) toks.size();
    if (toks.empty() || pos >= l->n_ctx - 8 || !decode_all(*l, toks)) {
        LOGE("prompt decode failed (pos=%d)", pos);
        return env->NewStringUTF("");
    }

    int budget = std::min<int>(max_tokens, l->n_ctx - pos - 1);
    std::string out;
    llama_sampler_reset(l->sampler);
    char piece[256];
    for (int i = 0; i < budget; ++i) {
        llama_token tok = llama_sampler_sample(l->sampler, l->ctx, -1);
        if (llama_vocab_is_eog(l->vocab, tok)) break;
        // special=true so the caller can see and strip any reasoning-channel markers.
        int n = llama_token_to_piece(l->vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, n);
        if (llama_decode(l->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    return to_jstring(env, out);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (l) free_llm(l);
}
