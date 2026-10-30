// JNI bridge between Kotlin (dev.offlineflow.llm.LlamaEngine) and llama.cpp.
//
// The caller formats prompts itself (each model family has its own chat format).
// Each fixed prefix (system prompt + few-shot examples; one per style) is decoded once and
// its sequence state is snapshotted. Each request restores a snapshot and decodes only
// the new turn. A snapshot (rather than trimming the KV cache) also works for models
// whose memory can't be partially rewound: hybrid/recurrent (Qwen3.5) and sliding-window
// attention (Gemma 4).
//
// Snapshots are also saved to disk (llama_state_seq_save_file) under a path the Kotlin side
// derives from the model file and the prefix text, so after the process is killed the prefix
// is read back instead of decoded again. A file is only used when its saved tokens match the
// prefix's tokens exactly.
//
// Generation can use prompt-lookup speculative decoding (ngram_spec.h): drafts copied from the
// transcript are verified in one batched decode, greedy-exact, rolled back with n_rs_seq
// snapshots on hybrid models. Any error falls back to plain decoding for that call.
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <string>
#include <vector>
#include "llama.h"
#include "ngram_spec.h"

#define TAG "hearsay-llm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Llm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    llama_sampler *sampler = nullptr;
    struct Prefix { std::vector<uint8_t> state; int n_tokens; std::vector<llama_token> tokens; };
    std::vector<Prefix> prefixes;  // sequence 0 state right after each prefix
    int n_ctx = 0;
    int n_batch = 0;
    // Speculative decoding: draft length the context was built for (0 = unavailable here),
    // and whether it is in use (pref; turned off for this model after an error).
    hearsay_spec::Config spec;
    int spec_capacity = 0;
    bool spec_broken = false;
    // The last completion, for the app's "Last cleanup" readout (LlamaEngine.lastRun).
    // mode: 0 speculation off, 1 on, 2 failed this call (fell back), 3 off since an earlier
    // failure, 4 unavailable for this model.
    int last[5] = {0, 0, 0, 0, 0};  // mode, tokens, drafted, accepted, generation ms
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
Java_dev_offlineflow_llm_LlamaEngine_nativeInit(JNIEnv *env, jclass, jstring jpath, jint threads, jint spec_draft) {
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
    // Hybrid/recurrent models keep this many per-token state snapshots so rejected drafts can be
    // rolled back (llama.cpp clamps it to 0 for models that can't, and attention KV needs none).
    cp.n_rs_seq = spec_draft > 0 ? (uint32_t) spec_draft : 0;
    l->ctx = llama_init_from_model(l->model, cp);
    if (!l->ctx && cp.n_rs_seq > 0) {
        LOGE("context init with n_rs_seq=%u failed, retrying without speculative decoding", cp.n_rs_seq);
        cp.n_rs_seq = 0;
        spec_draft = 0;
        l->ctx = llama_init_from_model(l->model, cp);
    }
    if (!l->ctx) { LOGE("context init failed"); free_llm(l); return 0; }
    if (spec_draft > 0) {
        bool recurrent = llama_model_is_recurrent(l->model) || llama_model_is_hybrid(l->model);
        if (recurrent && llama_n_rs_seq(l->ctx) < (uint32_t) spec_draft) {
            LOGI("speculative decoding unavailable: model state can't be rolled back");
        } else {
            l->spec_capacity = spec_draft;
            l->spec.draft_max = spec_draft;
            l->spec.ngram_min = 3; // a 3-token match before drafting (tools/eval/RESULTS.md)
            l->spec.ngram_max = 4;
            l->spec.use_prefix = true;
        }
    }
    l->n_ctx = (int) llama_n_ctx(l->ctx);
    l->n_batch = (int) cp.n_batch;

    l->sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(l->sampler, llama_sampler_init_greedy());

    LOGI("llm loaded: threads=%d spec_draft=%d", threads, l->spec_capacity);
    return reinterpret_cast<jlong>(l);
}

// Tries to restore sequence 0 from a saved prefix file. True only when the file's tokens are
// exactly [toks]; on any mismatch the sequence is cleared again so the caller can decode.
static bool load_prefix_file(Llm &l, const std::string &path, const std::vector<llama_token> &toks) {
    if (path.empty()) return false;
    FILE *f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::fclose(f);
    std::vector<llama_token> saved(toks.size() + 1);
    size_t n_saved = 0;
    size_t got = llama_state_seq_load_file(l.ctx, path.c_str(), 0, saved.data(), saved.size(), &n_saved);
    bool ok = got > 0 && n_saved == toks.size() && std::equal(toks.begin(), toks.end(), saved.begin());
    if (!ok) {
        llama_memory_seq_rm(llama_get_memory(l.ctx), 0, -1, -1);
        std::remove(path.c_str()); // stale or from another build: don't try it again
    }
    return ok;
}

static void save_prefix_file(Llm &l, const std::string &path, const std::vector<llama_token> &toks) {
    if (path.empty()) return;
    std::string tmp = path + ".tmp";
    size_t n = llama_state_seq_save_file(l.ctx, tmp.c_str(), 0, toks.data(), toks.size());
    if (n == 0 || std::rename(tmp.c_str(), path.c_str()) != 0) {
        std::remove(tmp.c_str());
        LOGE("could not save prefix to %s", path.c_str());
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeAddPrefix(JNIEnv *env, jobject, jlong handle, jstring jprefix, jstring jcache) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (!l) return -1;
    llama_memory_seq_rm(llama_get_memory(l->ctx), 0, -1, -1);
    auto toks = tokenize(l->vocab, jstr(env, jprefix), true);
    if (toks.empty() || (int) toks.size() >= l->n_ctx - 256) {
        LOGE("prefix too long or empty (%zu tokens)", toks.size());
        return -1;
    }
    std::string cache = jstr(env, jcache);
    bool from_disk = load_prefix_file(*l, cache, toks);
    if (!from_disk) {
        if (!decode_all(*l, toks)) { LOGE("prefix decode failed (%zu tokens)", toks.size()); return -1; }
        save_prefix_file(*l, cache, toks);
    }
    Llm::Prefix p;
    p.n_tokens = (int) toks.size();
    p.tokens = toks;
    p.state.resize(llama_state_seq_get_size(l->ctx, 0));
    size_t n = llama_state_seq_get_data(l->ctx, p.state.data(), p.state.size(), 0);
    if (n == 0) { LOGE("snapshot failed"); return -1; }
    p.state.resize(n);
    l->prefixes.push_back(std::move(p));
    LOGI("prefix %zu: %d tokens, %zu bytes%s", l->prefixes.size() - 1, l->prefixes.back().n_tokens, n,
         from_disk ? " (from disk)" : "");
    return (jint) (l->prefixes.size() - 1);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeComplete(JNIEnv *env, jobject, jlong handle,
                                                     jint prefix, jstring jsuffix, jint max_tokens, jboolean jspec) {
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
    // special=true so the caller can see and strip any reasoning-channel markers.
    auto emit = [&](llama_token tok) {
        int n = llama_token_to_piece(l->vocab, tok, piece, sizeof(piece), 0, true);
        if (n > 0) out.append(piece, n);
        return true;
    };
    bool spec = jspec && l->spec_capacity > 0 && !l->spec_broken;
    int mode = !jspec ? 0 : l->spec_capacity == 0 ? 4 : l->spec_broken ? 3 : 1;
    int tokens = 0;
    auto t0 = std::chrono::steady_clock::now();
    auto record = [&](int m, int drafted, int accepted) {
        int ms = (int) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
        l->last[0] = m; l->last[1] = tokens; l->last[2] = drafted; l->last[3] = accepted; l->last[4] = ms;
    };
    if (spec) {
        hearsay_spec::Stats st;
        int rc = hearsay_spec::SPEC_ERROR;
        try {
            rc = hearsay_spec::generate(l->ctx, l->sampler, l->vocab, pos, budget, toks, &pre.tokens, l->spec, st, emit);
        } catch (...) {
            rc = hearsay_spec::SPEC_ERROR;
        }
        if (rc == hearsay_spec::SPEC_OK) {
            LOGI("spec: %d tokens in %d steps, accepted %d/%d drafts (%.0f%%)", st.emitted, st.steps, st.accepted,
                 st.drafted, st.drafted ? 100.0 * st.accepted / st.drafted : 0.0);
            tokens = st.emitted;
            record(1, st.drafted, st.accepted);
            return to_jstring(env, out);
        }
        // Back to the prefix and decode normally; don't try drafts with this model again.
        LOGE("speculative decoding failed after %d tokens; plain decoding from now on", st.emitted);
        l->spec_broken = true;
        mode = 2;
        out.clear();
        if (!restore_prefix(*l, pre) || !decode_all(*l, toks)) { LOGE("fallback prompt decode failed"); return env->NewStringUTF(""); }
        llama_sampler_reset(l->sampler);
        t0 = std::chrono::steady_clock::now();
    }
    for (int i = 0; i < budget; ++i) {
        llama_token tok = llama_sampler_sample(l->sampler, l->ctx, -1);
        if (llama_vocab_is_eog(l->vocab, tok)) break;
        emit(tok);
        ++tokens;
        if (llama_decode(l->ctx, llama_batch_get_one(&tok, 1)) != 0) break;
    }
    record(mode, 0, 0);
    return to_jstring(env, out);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeLastRun(JNIEnv *env, jobject, jlong handle) {
    auto *l = reinterpret_cast<Llm *>(handle);
    jintArray a = env->NewIntArray(5);
    if (l && a) env->SetIntArrayRegion(a, 0, 5, l->last);
    return a;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_offlineflow_llm_LlamaEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *l = reinterpret_cast<Llm *>(handle);
    if (l) free_llm(l);
}
