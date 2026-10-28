cmake_minimum_required(VERSION 3.22.1)
project(offlineflow)

set(CMAKE_CXX_STANDARD 17)
set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} whisper)

add_library(offlineflow SHARED whisper_jni.cpp)
find_library(log-lib log)
target_link_libraries(offlineflow whisper ${log-lib})

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(offlineflow PRIVATE -O3)
endif()
