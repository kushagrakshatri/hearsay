plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Local LLM (llama.cpp) for transcript cleanup. Its own module so its ggml copy
// never collides with whisper.cpp's at the CMake target level.
android {
    namespace = "dev.offlineflow.llm"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
