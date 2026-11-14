// JNI for dev.offlineflow.asr.NativeParakeet: the app's own Parakeet runner (parakeet_core.cc) on the
// ONNX Runtime the sherpa-onnx AAR ships. Every entry point catches C++ exceptions and turns them into a
// Java RuntimeException, so a failure falls back to sherpa instead of crashing the process.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <cstring>
#include <exception>
#include <memory>
#include <string>
#include <vector>

#include "parakeet_core.h"

#define LOG_TAG "HearsayParakeet"

namespace {

struct Handle {
  std::unique_ptr<hearsay::Parakeet> pk;
  std::vector<float> layer11;  // last run's layer-11 rows (frames x 1024), kept only when asked for
  int32_t layer11_frames = 0;
};

std::string Str(JNIEnv *env, jstring s) {
  const char *c = env->GetStringUTFChars(s, nullptr);
  std::string r(c ? c : "");
  if (c) env->ReleaseStringUTFChars(s, c);
  return r;
}

void Throw(JNIEnv *env, const char *what) {
  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "%s", what);
  if (env->ExceptionCheck()) return;
  jclass c = env->FindClass("java/lang/RuntimeException");
  if (c) env->ThrowNew(c, what);
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_dev_offlineflow_asr_NativeParakeet_nativeInit(
    JNIEnv *env, jclass, jstring encoder, jstring decoder, jstring joiner, jint vocab, jint threads) {
  try {
    auto h = std::make_unique<Handle>();
    h->pk = std::make_unique<hearsay::Parakeet>(Str(env, encoder), Str(env, decoder), Str(env, joiner), vocab, threads);
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "loaded, %d threads, layer-11 output %s", threads,
                        h->pk->HasLayer11() ? "yes" : "no");
    return reinterpret_cast<jlong>(h.release());
  } catch (const std::exception &e) {
    Throw(env, (std::string("Parakeet load failed: ") + e.what()).c_str());
  } catch (...) {
    Throw(env, "Parakeet load failed");
  }
  return 0;
}

JNIEXPORT jboolean JNICALL Java_dev_offlineflow_asr_NativeParakeet_nativeHasLayer11(JNIEnv *, jclass, jlong handle) {
  auto *h = reinterpret_cast<Handle *>(handle);
  return h && h->pk->HasLayer11() ? JNI_TRUE : JNI_FALSE;
}

// Returns [numFrames, n, tokens[n], frames[n], durations[n], hasLayer11, fbankUs, encoderUs, decodeUs].
JNIEXPORT jintArray JNICALL Java_dev_offlineflow_asr_NativeParakeet_nativeRun(
    JNIEnv *env, jclass, jlong handle, jfloatArray samples, jboolean wantLayer11) {
  auto *h = reinterpret_cast<Handle *>(handle);
  if (!h) { Throw(env, "Parakeet is closed"); return nullptr; }
  try {
    jsize n = env->GetArrayLength(samples);
    std::vector<float> a(n);
    env->GetFloatArrayRegion(samples, 0, n, a.data());
    h->layer11.clear();
    h->layer11_frames = 0;
    auto r = h->pk->Run(a.data(), n, wantLayer11 == JNI_TRUE);
    if (r.has_layer11) {
      h->layer11 = std::move(r.layer11);
      h->layer11_frames = r.num_frames;
    }
    const int32_t k = static_cast<int32_t>(r.tokens.size());
    std::vector<jint> out;
    out.reserve(2 + 3 * k + 4);
    out.push_back(r.num_frames);
    out.push_back(k);
    out.insert(out.end(), r.tokens.begin(), r.tokens.end());
    out.insert(out.end(), r.frames.begin(), r.frames.end());
    out.insert(out.end(), r.durations.begin(), r.durations.end());
    out.push_back(r.has_layer11 ? 1 : 0);
    out.push_back(static_cast<jint>(r.ms_fbank * 1000));
    out.push_back(static_cast<jint>(r.ms_encoder * 1000));
    out.push_back(static_cast<jint>(r.ms_decode * 1000));
    jintArray j = env->NewIntArray(static_cast<jsize>(out.size()));
    if (j) env->SetIntArrayRegion(j, 0, static_cast<jsize>(out.size()), out.data());
    return j;
  } catch (const std::exception &e) {
    Throw(env, (std::string("Parakeet run failed: ") + e.what()).c_str());
  } catch (...) {
    Throw(env, "Parakeet run failed");
  }
  return nullptr;
}

// Layer-11 rows [from, to) of the last run (clipped to its frames), row-major x 1024; null when none were kept.
JNIEXPORT jfloatArray JNICALL Java_dev_offlineflow_asr_NativeParakeet_nativeLayer11Rows(
    JNIEnv *env, jclass, jlong handle, jint from, jint to) {
  auto *h = reinterpret_cast<Handle *>(handle);
  if (!h || h->layer11_frames == 0) return nullptr;
  int32_t a = std::max<int32_t>(0, from), b = std::min<int32_t>(to, h->layer11_frames);
  if (b <= a) return env->NewFloatArray(0);
  const jsize len = static_cast<jsize>((b - a) * hearsay::Parakeet::kLayer11Dim);
  jfloatArray j = env->NewFloatArray(len);
  if (j) env->SetFloatArrayRegion(j, 0, len, h->layer11.data() + static_cast<size_t>(a) * hearsay::Parakeet::kLayer11Dim);
  return j;
}

JNIEXPORT void JNICALL Java_dev_offlineflow_asr_NativeParakeet_nativeFree(JNIEnv *, jclass, jlong handle) {
  delete reinterpret_cast<Handle *>(handle);
}

}  // extern "C"
