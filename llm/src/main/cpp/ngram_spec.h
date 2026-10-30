// Prompt-lookup (n-gram) speculative decoding for greedy cleanup, shared by the app's JNI bridge
// (llama_jni.cpp) and the desktop eval twin (tools/eval/hearsay_llm.cpp).
//
// Cleanup output mostly copies the transcript. After each emitted token we take the last n
// generated tokens (n = ngram_max down to ngram_min), look for them in the source tokens (the
// user turn first, then our own earlier output, then the cached prefix), and propose the tokens
// that followed as a draft. The pending token plus the draft are decoded in one batch with
// logits for every position; draft tokens are accepted while they equal what the greedy sampler
// picks at the previous position, so the output is the same token sequence plain greedy decoding
// produces. Rejected positions are removed with llama_memory_seq_rm: attention KV is trimmed
// directly; hybrid/recurrent models (Qwen3.5, LFM2) need the context created with
// n_rs_seq >= draft_max so llama.cpp keeps per-token recurrent snapshots to roll back to.
//
// Any llama.cpp error returns SPEC_ERROR; the caller restores its prefix and decodes normally.
#pragma once
#include <algorithm>
#include <vector>
#include "llama.h"

namespace hearsay_spec {

struct Config {
    int draft_max = 3;    // tokens proposed per step (context needs n_rs_seq >= this for hybrid models)
    int ngram_min = 3;    // shortest suffix of the output that may match
    int ngram_max = 4;    // longest suffix tried first
    bool use_prefix = true; // also search the cached prefix (system prompt + few-shots)
    bool adaptive = false;  // shrink the draft after a rejection, grow it back after full acceptance
};

struct Stats {
    int steps = 0;     // batched verify decodes
    int drafted = 0;   // draft tokens proposed
    int accepted = 0;  // draft tokens accepted
    int emitted = 0;   // tokens emitted
};

enum Result { SPEC_OK = 0, SPEC_ERROR = -1 };

// Looks for the last n tokens of gen in src (only matches that have at least one following token,
// and, for the output itself, that end before its last token). Most recent match wins.
inline bool find_continuation(const std::vector<llama_token> &src, size_t src_end, const std::vector<llama_token> &gen,
                              int n, int want, std::vector<llama_token> &draft) {
    if ((int) gen.size() < n || src_end < (size_t) n + 1) return false;
    const llama_token *key = gen.data() + gen.size() - n;
    for (size_t end = src_end - 1; end >= (size_t) n; --end) { // match = src[end-n, end), continuation from src[end]
        if (src[end - 1] != key[n - 1]) continue;
        if (!std::equal(key, key + n, src.begin() + (end - n))) continue;
        draft.clear();
        for (size_t k = end; k < src_end && (int) draft.size() < want; ++k) draft.push_back(src[k]);
        return !draft.empty();
    }
    return false;
}

inline void make_draft(const std::vector<llama_token> &user, const std::vector<llama_token> *prefix,
                       const std::vector<llama_token> &gen, const Config &cfg, int want, std::vector<llama_token> &draft) {
    draft.clear();
    if (want <= 0) return;
    for (int n = cfg.ngram_max; n >= cfg.ngram_min; --n) {
        if (find_continuation(user, user.size(), gen, n, want, draft)) return;
        if (gen.size() > (size_t) n && find_continuation(gen, gen.size() - 1, gen, n, want, draft)) return;
        if (prefix && find_continuation(*prefix, prefix->size(), gen, n, want, draft)) return;
    }
}

// Greedy generation with n-gram drafts. The context must hold the prompt (prefix + user turn) at
// positions [0, pos) with logits for the last prompt token available at index -1.
// emit(tok) appends a non-EOG token and returns false to stop (stop string hit). Generation also
// stops on an EOG token or after `budget` tokens, exactly where the plain loop would.
template <class Emit>
int generate(llama_context *ctx, llama_sampler *smpl, const llama_vocab *vocab, int pos, int budget,
             const std::vector<llama_token> &user, const std::vector<llama_token> *prefix, const Config &cfg,
             Stats &st, Emit emit) {
    const int n_ctx = (int) llama_n_ctx(ctx);
    const int dmax = std::max(0, cfg.draft_max);
    llama_batch batch = llama_batch_init(dmax + 1, 0, 1);
    std::vector<llama_token> gen, draft;
    gen.reserve(budget + 1);
    int rc = SPEC_OK;
    int want_len = dmax; // adaptive draft length
    llama_token cur = llama_sampler_sample(smpl, ctx, -1);
    // Emits one token; false when generation is over.
    auto take = [&](llama_token t) {
        if (llama_vocab_is_eog(vocab, t)) return false;
        gen.push_back(t);
        st.emitted++;
        if (!emit(t)) return false;
        return (int) gen.size() < budget;
    };
    if (take(cur)) {
        while (true) {
            int room = std::min(budget - (int) gen.size() - 1, n_ctx - pos - 2);
            make_draft(user, cfg.use_prefix ? prefix : nullptr, gen, cfg, std::min(cfg.adaptive ? want_len : dmax, room), draft);
            batch.n_tokens = 0;
            auto add = [&](llama_token t, int p) {
                int i = batch.n_tokens++;
                batch.token[i] = t; batch.pos[i] = p; batch.n_seq_id[i] = 1; batch.seq_id[i][0] = 0; batch.logits[i] = 1;
            };
            add(cur, pos);
            for (size_t k = 0; k < draft.size(); ++k) add(draft[k], pos + 1 + (int) k);
            if (llama_decode(ctx, batch) != 0) { rc = SPEC_ERROR; break; }
            st.steps++;
            st.drafted += (int) draft.size();
            int acc = 0;
            bool more = true;
            llama_token next = llama_sampler_sample(smpl, ctx, 0);
            while (acc < (int) draft.size() && next == draft[acc]) {
                acc++;
                st.accepted++;
                if (!take(next)) { more = false; break; }
                next = llama_sampler_sample(smpl, ctx, acc);
            }
            if (!more) break;
            if (cfg.adaptive && !draft.empty())
                want_len = acc == (int) draft.size() ? std::min(dmax, want_len * 2) : std::max(2, acc + 1);
            pos += 1 + acc;
            if (acc < (int) draft.size() && !llama_memory_seq_rm(llama_get_memory(ctx), 0, pos, -1)) { rc = SPEC_ERROR; break; }
            cur = next;
            if (!take(cur)) break;
        }
    }
    llama_batch_free(batch);
    return rc;
}

} // namespace hearsay_spec
