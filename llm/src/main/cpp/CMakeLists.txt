cmake_minimum_required(VERSION 3.22.1)
project(hearsayllm)

set(CMAKE_CXX_STANDARD 17)
set(LLAMA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/llama.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

# Same reasoning as the whisper build: always optimize, even in debug APKs.
add_compile_options(-O3 -DNDEBUG)
if (ANDROID_ABI STREQUAL "arm64-v8a")
    add_compile_options(-march=armv8.2-a+fp16+dotprod)
endif()

add_subdirectory(${LLAMA_DIR} llama)

add_library(hearsayllm SHARED llama_jni.cpp)
find_library(log-lib log)
target_link_libraries(hearsayllm llama ${log-lib})
# Keep this library's statically linked ggml private (whisper ships its own copy).
target_link_options(hearsayllm PRIVATE "-Wl,--exclude-libs,ALL")
