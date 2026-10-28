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
endif()

add_subdirectory(${WHISPER_DIR} whisper)

add_library(offlineflow SHARED whisper_jni.cpp)
find_library(log-lib log)
target_link_libraries(offlineflow whisper ${log-lib})
# Keep this library's statically linked ggml private (the llm module ships its own copy).
target_link_options(offlineflow PRIVATE "-Wl,--exclude-libs,ALL")
