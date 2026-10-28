import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// sherpa-onnx (Parakeet / Moonshine runtime) ships as a prebuilt AAR on GitHub, not Maven.
// Fetched once into app/libs and pinned by SHA-256.
val sherpaVersion = "2.0.1"
val sherpaSha256 = "a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7"
val sherpaAar = file("libs/sherpa-onnx-$sherpaVersion.aar")
fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").let { md ->
    f.inputStream().use { input ->
        val buf = ByteArray(1 shl 16)
        while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
    }
    md.digest().joinToString("") { "%02x".format(it) }
}
if (!sherpaAar.exists() || sha256(sherpaAar) != sherpaSha256) {
    sherpaAar.parentFile.mkdirs()
    val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/sherpa-onnx-$sherpaVersion.aar"
    logger.lifecycle("Downloading $url")
    uri(url).toURL().openStream().use { input -> sherpaAar.outputStream().use { input.copyTo(it) } }
    check(sha256(sherpaAar) == sherpaSha256) { "sherpa-onnx AAR checksum mismatch" }
}

// CI run number makes every published build an in-place upgrade.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.offlineflow"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "dev.offlineflow"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.0-build.$buildNumber"
        // Opt-in cloud mode (bring your own key). Build with -PnoCloud to compile it out (F-Droid).
        buildConfigField("boolean", "CLOUD", if (project.hasProperty("noCloud")) "false" else "true")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared") }
        }
    }

    // Fixed, committed debug key so every CI build installs over the previous one.
    // Debug-only and public on purpose; real releases will use a private key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("hearsay-debug.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // Both native modules bundle the same NDK C++ runtime.
        jniLibs { pickFirsts += "**/libc++_shared.so" }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // The sherpa-onnx AAR may be compiled with a newer Kotlin than ours.
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(files(sherpaAar))
    implementation(project(":llm"))
    testImplementation("junit:junit:4.13.2")
}
