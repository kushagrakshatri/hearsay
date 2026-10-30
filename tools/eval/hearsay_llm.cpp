// Desktop twin of llm/src/main/cpp/llama_jni.cpp, for offline cleanup evals (tools/eval/run_eval.py).
// Same llama.cpp calls and settings as the app: ctx 2048, batch/ubatch 512, one sequence,
// prefix tokenized with add_special=true (BOS when the model wants one), state snapshot per
// prefix, suffix tokenized without specials, greedy sampling, stop on any end-of-generation token.
// Optional repetition penalty (argv[3]) to test models that loop under pure greedy.
// Optional sampling (argv[4..6] = temperature top_k seed): when temperature > 0 the chain is
// top-k -> penalties -> temp -> dist(seed) instead of greedy (for vendor-recommended settings).
//
// Protocol on stdin/stdout (byte-counted so text can hold newlines):
//   P <nbytes>\n<prefix bytes>                 -> "P <id> <ntokens> <ms>\n"
//   C <prefixId> <maxTokens> <nbytes>\n<bytes> -> "C <promptTokens> <prefillMs> <genTokens> <decodeMs> <nbytes>\n<out bytes>"
//   T <nbytes>\n<bytes>                        -> "T <ntokens>\n"  (token count with add_special=true)
//   S <nbytes>\n<stops separated by \x1f>      -> "S <nstops>\n"  stop strings for later C calls: generation
//                                                 ends at the first stop, which is cut from the output
//                                                 (for plain-completion prompts with no end-of-turn token)
//   Q\n                                        -> "Q <steps> <drafted> <accepted> <fallback>\n" speculative stats of the last C
//   X <draft_max> <ngram_min> <use_prefix>\n   -> "X <on>\n" change the speculative settings for later C calls (draft_max 0 =
//                                                 plain decoding; capped at the HEARSAY_SPEC draft_max the context was built for),
//                                                 for paired A/B timing in one process (tools/eval/spec_ab.py)
//
// Speculative decoding (prompt lookup, llm/src/main/cpp/ngram_spec.h, same code as the app): set
// HEARSAY_SPEC=draft_max,ngram_min[,use_prefix[,adaptive]] in the environment. Greedy only (ignored with a
// repetition penalty or sampling). The context is created with n_rs_seq = draft_max so hybrid
// models can roll back rejected drafts. Build with -Illm/src/main/cpp.
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <string>
#include <vector>
#include "llama.h"
#include "ngram_spec.h"

using clk = std::chrono::steady_clock;
static double ms_since(clk::time_point t) { return std::chrono::duration<double, std::milli>(clk::now() - t).count(); }

struct Prefix { std::vector<uint8_t> state; int n_tokens; std::vector<llama_token> tokens; };

static std::vector<llama_token> tokenize(const llama_vocab *v, const std::string &s, bool add_special) {
    int n = -llama_tokenize(v, s.c_str(), (int32_t) s.size(), nullptr, 0, add_special, true);
    if (n <= 0) return {};
    std::vector<llama_token> t(n);
    int got = llama_tokenize(v, s.c_str(), (int32_t) s.size(), t.data(), n, add_special, true);
    if (got < 0) return {};
    t.resize(got);
    return t;
}

static bool read_bytes(size_t n, std::string &out) {
    out.resize(n);
    return n == 0 || std::fread(out.data(), 1, n, stdin) == n;
}

int main(int argc, char **argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: %s model.gguf threads [repeat_penalty [temp top_k seed]]\n", argv[0]); return 2; }
    float rep = argc > 3 ? std::atof(argv[3]) : 1.0f;
    float temp = argc > 4 ? std::atof(argv[4]) : 0.0f;
    int top_k = argc > 5 ? std::atoi(argv[5]) : 50;
    uint32_t seed = argc > 6 ? (uint32_t) std::strtoul(argv[6], nullptr, 10) : 42;
    hearsay_spec::Config spec;
    bool use_spec = false;
    if (const char *e = std::getenv("HEARSAY_SPEC")) {
        int up = 1, ad = 0;
        if (std::sscanf(e, "%d,%d,%d,%d", &spec.draft_max, &spec.ngram_min, &up, &ad) >= 2 && spec.draft_max > 0 && spec.ngram_min > 0) {
            use_spec = rep == 1.0f && temp <= 0.0f;
            spec.use_prefix = up != 0;
            spec.adaptive = ad != 0;
            spec.ngram_max = std::max(spec.ngram_min, 4);
        }
    }
    llama_log_set([](ggml_log_level, const char *, void *) {}, nullptr);
    llama_backend_init();
    auto mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(argv[1], mp);
    if (!model) { std::fprintf(stderr, "load failed\n"); return 1; }
    const llama_vocab *vocab = llama_model_get_vocab(model);
    auto cp = llama_context_default_params();
    cp.n_ctx = 2048; cp.n_batch = 512; cp.n_ubatch = 512; cp.n_seq_max = 1;
    cp.n_threads = cp.n_threads_batch = std::atoi(argv[2]);
    cp.n_rs_seq = use_spec ? (uint32_t) spec.draft_max : 0; // recurrent snapshots to roll back rejected drafts
    llama_context *ctx = llama_init_from_model(model, cp);
    int n_ctx = (int) llama_n_ctx(ctx), n_batch = 512;
    llama_sampler *smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (rep != 1.0f || temp > 0.0f) {
        // top-k first: penalties scan the candidate list (slow on the full vocabulary).
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(temp > 0.0f ? top_k : 50));
        if (rep != 1.0f) llama_sampler_chain_add(smpl, llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, rep, 0.0f, 0.0f));
    }
    if (temp > 0.0f) {
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(temp));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(seed));
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    }
    std::vector<Prefix> prefixes;
    std::vector<std::string> stops;
    hearsay_spec::Stats last_stats; int last_fallback = 0;

    auto decode_all = [&](std::vector<llama_token> &t) {
        for (size_t i = 0; i < t.size(); i += n_batch) {
            int n = (int) std::min<size_t>(n_batch, t.size() - i);
            if (llama_decode(ctx, llama_batch_get_one(t.data() + i, n)) != 0) return false;
        }
        return true;
    };

    char line[256];
    while (std::fgets(line, sizeof line, stdin)) {
        std::string body;
        if (line[0] == 'P') {
            size_t n = std::strtoul(line + 2, nullptr, 10);
            read_bytes(n, body);
            auto t0 = clk::now();
            llama_memory_seq_rm(llama_get_memory(ctx), 0, -1, -1);
            auto toks = tokenize(vocab, body, true);
            if (toks.empty() || (int) toks.size() >= n_ctx - 256 || !decode_all(toks)) { std::printf("P -1 %zu 0\n", toks.size()); std::fflush(stdout); continue; }
            Prefix p; p.n_tokens = (int) toks.size(); p.tokens = toks;
            p.state.resize(llama_state_seq_get_size(ctx, 0));
            p.state.resize(llama_state_seq_get_data(ctx, p.state.data(), p.state.size(), 0));
            prefixes.push_back(std::move(p));
            std::printf("P %zu %zu %.1f\n", prefixes.size() - 1, toks.size(), ms_since(t0));
        } else if (line[0] == 'S') {
            size_t n = std::strtoul(line + 2, nullptr, 10);
            read_bytes(n, body);
            stops.clear();
            size_t a = 0;
            while (a <= body.size()) {
                size_t b = body.find('\x1f', a);
                if (b == std::string::npos) b = body.size();
                if (b > a) stops.push_back(body.substr(a, b - a));
                a = b + 1;
            }
            std::printf("S %zu\n", stops.size());
        } else if (line[0] == 'T') {
            size_t n = std::strtoul(line + 2, nullptr, 10);
            read_bytes(n, body);
            std::printf("T %zu\n", tokenize(vocab, body, true).size());
        } else if (line[0] == 'C') {
            int id = 0, max_tokens = 0; size_t n = 0;
            std::sscanf(line + 2, "%d %d %zu", &id, &max_tokens, &n);
            read_bytes(n, body);
            const Prefix &pre = prefixes.at(id);
            auto t0 = clk::now();
            llama_memory_seq_rm(llama_get_memory(ctx), 0, -1, -1);
            llama_state_seq_set_data(ctx, pre.state.data(), pre.state.size(), 0);
            auto toks = tokenize(vocab, body, false);
            int pos = pre.n_tokens + (int) toks.size();
            std::string out;
            int gen = 0; double prefill = 0, dec = 0;
            if (!toks.empty() && pos < n_ctx - 8 && decode_all(toks)) {
                prefill = ms_since(t0);
                auto t1 = clk::now();
                int budget = std::min(max_tokens, n_ctx - pos - 1);
                llama_sampler_reset(smpl);
                char piece[256];
                // Appends a token; false when a stop string ends generation.
                auto emit = [&](llama_token tok) {
                    int k = llama_token_to_piece(vocab, tok, piece, sizeof piece, 0, true);
                    if (k > 0) out.append(piece, k);
                    gen++;
                    for (auto &st : stops) {
                        size_t at = out.find(st);
                        if (at != std::string::npos) { out.resize(at); return false; }
                    }
                    return true;
                };
                auto plain = [&]() {
                    for (int i = 0; i < budget; ++i) {
                        llama_token tok = llama_sampler_sample(smpl, ctx, -1);
                        if (llama_vocab_is_eog(vocab, tok)) break;
                        if (!emit(tok)) break;
                        if (llama_decode(ctx, llama_batch_get_one(&tok, 1)) != 0) break;
                    }
                };
                last_stats = {}; last_fallback = 0;
                if (use_spec) {
                    int rc = hearsay_spec::generate(ctx, smpl, vocab, pos, budget, toks, &pre.tokens, spec, last_stats, emit);
                    if (rc != hearsay_spec::SPEC_OK) {
                        // Same fallback as the app: back to the prefix, prompt again, plain decode.
                        last_fallback = 1; out.clear(); gen = 0;
                        llama_memory_seq_rm(llama_get_memory(ctx), 0, -1, -1);
                        llama_state_seq_set_data(ctx, pre.state.data(), pre.state.size(), 0);
                        if (decode_all(toks)) { llama_sampler_reset(smpl); plain(); }
                    }
                } else {
                    plain();
                }
                dec = ms_since(t1);
            }
            std::printf("C %zu %.1f %d %.1f %zu\n", toks.size(), prefill, gen, dec, out.size());
            std::fwrite(out.data(), 1, out.size(), stdout);
        } else if (line[0] == 'X') {
            int d = 0, g = 2, up = 1;
            std::sscanf(line + 2, "%d %d %d", &d, &g, &up);
            int cap = (int) llama_n_rs_seq(ctx) > 0 ? (int) llama_n_rs_seq(ctx) : (cp.n_rs_seq > 0 ? (int) cp.n_rs_seq : 0);
            use_spec = d > 0 && cap > 0 && rep == 1.0f && temp <= 0.0f;
            if (use_spec) { spec.draft_max = std::min(d, cap); spec.ngram_min = std::max(1, g); spec.ngram_max = std::max(spec.ngram_min, 4); spec.use_prefix = up != 0; spec.adaptive = false; }
            std::printf("X %d\n", use_spec ? 1 : 0);
        } else if (line[0] == 'Q') {
            std::printf("Q %d %d %d %d\n", last_stats.steps, last_stats.drafted, last_stats.accepted, last_fallback);
        }
        std::fflush(stdout);
    }
    llama_sampler_free(smpl); llama_free(ctx); llama_model_free(model);
    return 0;
}
