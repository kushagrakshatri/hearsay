// JNI bridge between Kotlin (dev.offlineflow.llm.LlamaEngine) and llama.cpp.
//
// The fixed part of the prompt (system message + few-shot examples) is evaluated once
// at load and kept in the KV cache. Each request only removes what came after that
// prefix and decodes the new user turn, so cleanup cost scales with the utterance,
// not with the prompt.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <algorithm>
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
    std::string tmpl;                     // chat template (or a built-in name)
    std::vector<std::string> roles;       // fixed prefix messages
    std::vector<std::string> contents;
    std::string prefix_text;
    int n_prefix = 0;
    int n_ctx = 0;
    int n_batch = 0;
};

std::string apply_template(const Llm &l, const std::vector<std::string> &roles,
                           const std::vector<std::string> &contents, bool add_ass) {
    std::vector<llama_chat_message> msgs;
    msgs.reserve(roles.size());
    for (size_t i = 0; i < roles.size(); ++i) msgs.push_back({roles[i].c_str(), contents[i].c_str()});
    size_t chars = 0;
    for (auto &c : contents) chars += c.size();
    std::vector<char> buf(chars * 2 + 1024);
    int n = llama_chat_apply_template(l.tmpl.c_str(), msgs.data(), msgs.size(), add_ass,
                                      buf.data(), (int32_t) buf.size());
    if (n < 0) return {};
    if (n > (int) buf.size()) {
        buf.resize(n);
        n = llama_chat_apply_template(l.tmpl.c_str(), msgs.data(), msgs.size(), add_ass,
                                      buf.data(), (int32_t) buf.size());
    }
    return std::string(buf.data(), n);
}

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

bool eval_prefix(Llm &l) {
    llama_memory_clear(llama_get_memory(l.ctx), true);
    l.prefix_text = apply_template(l, l.roles, l.contents, false);
    auto toks = tokenize(l.vocab, l.prefix_text, true);
    if (toks.empty() || !decode_all(l, toks)) return false;
    l.n_prefix = (int) toks.size();
    return true;
}

std::string jstr(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeInit(JNIEnv *env, jclass, jstring jpath, jint threads,
                                                 jstring jsystem, jobjectArray jexamples) {
    static bool backend = false;
    if (!backend) { llama_backend_init(); backend = true; }

    auto *l = new Llm();
    std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    l->model = llama_model_load_from_file(path.c_str(), mp);
    if (!l->model) { LOGE("model load failed: %s", path.c_str()); delete l; return 0; }
    l->vocab = llama_model_get_vocab(l->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 2048;
    cp.n_batch = 512;
    cp.n_ubatch = 512;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    l->ctx = llama_init_from_model(l->model, cp);
    if (!l->ctx) { LOGE("context init failed"); llama_model_free(l->model); delete l; return 0; }
    l->n_ctx = (int) cp.n_ctx;
    l->n_batch = (int) cp.n_batch;

    l->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(l->sampler, llama_sampler_init_greedy());

    const char *t = llama_model_chat_template(l->model, nullptr);
    l->tmpl = t ? t : "chatml";

    l->roles.emplace_back("system");
    l->contents.push_back(jstr(env, jsystem));
    jsize n = jexamples ? env->GetArrayLength(jexamples) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto s = (jstring) env->GetObjectArrayElement(jexamples, i);
        l->roles.emplace_back(i % 2 == 0 ? "user" : "assistant");
        l->contents.push_back(jstr(env, s));
        env->DeleteLocalRef(s);
    }

    if (!eval_prefix(*l)) {
        LOGE("prefix eval failed");
        llama_sampler_free(l->sampler); llama_free(l->ctx); llama_model_free(l->model);
        delete l;
        return 0;
    }
    LOGI("llm ready: prefix=%d tokens, threads=%d", l->n_prefix, threads);
    return reinterpret_cast<jlong>(l);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeComplete(JNIEnv *env, jobject, jlong handle,
                                                     jstring juser, jint max_tokens) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (!l) return env->NewStringUTF("");

    auto roles = l->roles;
    auto contents = l->contents;
    roles.emplace_back("user");
    contents.push_back(jstr(env, juser));
    std::string full = apply_template(*l, roles, contents, true);

    llama_memory_t mem = llama_get_memory(l->ctx);
    std::vector<llama_token> toks;
    if (full.compare(0, l->prefix_text.size(), l->prefix_text) == 0) {
        // Reuse the cached prefix; drop the previous request's tokens.
        if (!llama_memory_seq_rm(mem, 0, l->n_prefix, -1) && !eval_prefix(*l)) return env->NewStringUTF("");
        toks = tokenize(l->vocab, full.substr(l->prefix_text.size()), false);
    } else {
        // Template didn't split cleanly at the prefix; evaluate everything.
        llama_memory_clear(mem, true);
        l->n_prefix = 0;
        l->prefix_text.clear();
        toks = tokenize(l->vocab, full, true);
    }
    int pos = (l->n_prefix > 0 ? l->n_prefix : 0) + (int) toks.size();
    if (toks.empty() || pos >= l->n_ctx - 8 || !decode_all(*l, toks)) {
        LOGE("prompt decode failed (pos=%d)", pos);
        if (l->n_prefix == 0) eval_prefix(*l);
        return env->NewStringUTF("");
    }

    int budget = std::min<int>(max_tokens, l->n_ctx - pos - 1);
    std::string out;
    llama_sampler_reset(l->sampler);
    char piece[256];
    for (int i = 0; i < budget; ++i) {
        llama_token tok = llama_sampler_sample(l->sampler, l->ctx, -1);
        if (llama_vocab_is_eog(l->vocab, tok)) break;
        int n = llama_token_to_piece(l->vocab, tok, piece, sizeof(piece), 0, false);
        if (n > 0) out.append(piece, n);
        if (llama_decode(l->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    if (l->n_prefix == 0) eval_prefix(*l); // restore the cached prefix for next time

    // Return as Java String via UTF-16 conversion of (possibly modified-UTF-8-unsafe) bytes.
    jbyteArray bytes = env->NewByteArray((jsize) out.size());
    env->SetByteArrayRegion(bytes, 0, (jsize) out.size(), reinterpret_cast<const jbyte *>(out.data()));
    jclass strCls = env->FindClass("java/lang/String");
    jmethodID ctor = env->GetMethodID(strCls, "<init>", "([BLjava/lang/String;)V");
    jstring enc = env->NewStringUTF("UTF-8");
    auto result = (jstring) env->NewObject(strCls, ctor, bytes, enc);
    env->DeleteLocalRef(bytes);
    env->DeleteLocalRef(enc);
    env->DeleteLocalRef(strCls);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (!l) return;
    llama_sampler_free(l->sampler);
    llama_free(l->ctx);
    llama_model_free(l->model);
    delete l;
}
