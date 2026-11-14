cmake_minimum_required(VERSION 3.22.1)
project(offlineflow)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Always optimize native code, even in debug APKs: whisper.cpp at -O0 is 10-50x
# slower and looks "stuck" on Transcribing. Options added here come after the
# config flags (-O0 -g), so they win.
add_compile_options(-O3 -DNDEBUG)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
elseif (ANDROID_ABI STREQUAL "x86_64")
    # Debug-only ABI (emulator). CI hosts have AVX2; without it inference crawls.
    add_compile_options(-mavx2 -mfma -mf16c)
endif()

add_subdirectory(${WHISPER_DIR} whisper)

add_library(offlineflow SHARED whisper_jni.cpp)
find_library(log-lib log)
target_link_libraries(offlineflow whisper ${log-lib})
# Keep this library's statically linked ggml private (the llm module ships its own copy).
target_link_options(offlineflow PRIVATE "-Wl,--exclude-libs,ALL")

# ---- Parakeet on ONNX Runtime ("own engine"): fbank + encoder + TDT greedy, see parakeet_core.cc.
# Links against the libonnxruntime.so inside the pinned sherpa-onnx AAR (ORT 1.28.2), which app/build.gradle.kts
# unzips to SHERPA_ORT_DIR for linking only; the APK carries that one copy (sherpa needs it anyway).
set(TP ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party)
set(SHERPA_ORT_DIR "" CACHE PATH "Directory holding <abi>/libonnxruntime.so from the sherpa-onnx AAR")
set(ORT_SO ${SHERPA_ORT_DIR}/${ANDROID_ABI}/libonnxruntime.so)
if (NOT EXISTS ${ORT_SO})
    message(FATAL_ERROR "libonnxruntime.so not found at ${ORT_SO} (extracted from the sherpa-onnx AAR by app/build.gradle.kts)")
endif()
add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION ${ORT_SO})

add_library(pk_kissfft STATIC ${TP}/kissfft/kiss_fft.c ${TP}/kissfft/kiss_fftr.c)
target_compile_definitions(pk_kissfft PUBLIC kiss_fft_scalar=float)
target_include_directories(pk_kissfft PUBLIC ${TP}/kissfft)
set_target_properties(pk_kissfft PROPERTIES POSITION_INDEPENDENT_CODE ON)

set(KNF ${TP}/kaldi-native-fbank/kaldi-native-fbank/csrc)
add_library(pk_knf STATIC
    ${KNF}/feature-fbank.cc ${KNF}/feature-functions.cc ${KNF}/feature-mfcc.cc ${KNF}/feature-raw-audio-samples.cc
    ${KNF}/feature-window.cc ${KNF}/istft.cc ${KNF}/kaldi-math.cc ${KNF}/mel-computations.cc ${KNF}/online-feature.cc
    ${KNF}/rfft.cc ${KNF}/stft.cc ${KNF}/whisper-feature.cc)
target_include_directories(pk_knf PUBLIC ${TP}/kaldi-native-fbank)
target_link_libraries(pk_knf PUBLIC pk_kissfft)
set_target_properties(pk_knf PROPERTIES POSITION_INDEPENDENT_CODE ON)

add_library(parakeet SHARED parakeet_jni.cpp parakeet_core.cc)
target_include_directories(parakeet PRIVATE ${TP}/onnxruntime-headers/include)
target_link_libraries(parakeet pk_knf onnxruntime ${log-lib})
# knf/kissfft stay private to this library (sherpa's own JNI carries its own copy of knf).
target_link_options(parakeet PRIVATE "-Wl,--exclude-libs,ALL")
