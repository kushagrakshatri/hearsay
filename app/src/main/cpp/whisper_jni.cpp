// JNI bridge between Kotlin (dev.offlineflow.asr.WhisperEngine) and whisper.cpp.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <thread>
#include <algorithm>
#include "whisper.h"

#define TAG "offlineflow-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

extern "C" JNIEXPORT jlong JNICALL
Java_dev_offlineflow_asr_WhisperEngine_nativeInit(JNIEnv *env, jclass, jstring jpath) {
    const char *path = env->GetStringUTFChars(jpath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);
    LOGI("model loaded: %s", ctx ? "ok" : "FAILED");
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_offlineflow_asr_WhisperEngine_nativeTranscribe(
        JNIEnv *env, jobject, jlong handle, jfloatArray jsamples, jstring jprompt) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (!ctx) return env->NewStringUTF("");

    jsize n = env->GetArrayLength(jsamples);
    jfloat *samples = env->GetFloatArrayElements(jsamples, nullptr);

    const char *prompt = jprompt ? env->GetStringUTFChars(jprompt, nullptr) : nullptr;

    whisper_full_params p = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    p.n_threads = std::max(1, std::min(4, (int) std::thread::hardware_concurrency() - 1));
    p.language = "en";
    p.translate = false;
    p.no_context = true;
    p.single_segment = false;
    p.print_progress = false;
    p.print_realtime = false;
    p.print_timestamps = false;
    p.suppress_blank = true;
    p.initial_prompt = prompt; // personal dictionary words bias spelling

    int rc = whisper_full(ctx, p, samples, n);

    env->ReleaseFloatArrayElements(jsamples, samples, JNI_ABORT);
    if (prompt) env->ReleaseStringUTFChars(jprompt, prompt);

    std::string out;
    if (rc == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; ++i) out += whisper_full_get_segment_text(ctx, i);
    }
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_dev_offlineflow_asr_WhisperEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    auto *ctx = reinterpret_cast<whisper_context *>(handle);
    if (ctx) whisper_free(ctx);
}
