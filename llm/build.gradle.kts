plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// Local LLM for transcript cleanup: llama.cpp (GGUF, CPU) and LiteRT-LM (.litertlm, GPU/CPU). Its own module so its ggml copy
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
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    // Google LiteRT-LM: GPU (OpenCL) / CPU inference for .litertlm models. Prebuilt arm64 + x86_64 JNI.
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.19.0")
}
