import java.security.MessageDigest
import java.util.zip.ZipFile

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

// The app's own Parakeet runner (src/main/cpp/parakeet_core.cc) links against the ONNX Runtime inside that same
// AAR (1.28.2). Unzip jni/<abi>/libonnxruntime.so into build/ for CMake to link against; nothing extra is packaged
// (the AAR's copy is the one in the APK). Keyed on the AAR's pinned SHA, so a new AAR re-extracts.
val sherpaOrtDir = layout.buildDirectory.dir("sherpa-ort/$sherpaSha256").get().asFile
for (abi in listOf("arm64-v8a", "x86_64")) {
    val so = File(sherpaOrtDir, "$abi/libonnxruntime.so")
    if (so.exists()) continue
    so.parentFile.mkdirs()
    ZipFile(sherpaAar).use { zip ->
        val e = checkNotNull(zip.getEntry("jni/$abi/libonnxruntime.so")) { "sherpa-onnx AAR has no jni/$abi/libonnxruntime.so" }
        val tmp = File(so.path + ".tmp")
        zip.getInputStream(e).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        check(tmp.renameTo(so)) { "Could not extract $so" }
    }
}

// Testing diagnostics (Sentry). The DSN comes from the HEARSAY_SENTRY_DSN environment variable (a CI secret); empty
// means the SDK is never started and nothing is sent. -PnoTelemetry compiles it all out, Sentry library included
// (public Play / F-Droid builds), the way -PnoCloud does for cloud features.
val telemetry = !project.hasProperty("noTelemetry")
val sentryDsn = if (telemetry) System.getenv("HEARSAY_SENTRY_DSN").orEmpty().trim() else ""
check(sentryDsn.none { it == '"' || it == '\\' }) { "HEARSAY_SENTRY_DSN has a quote or backslash" }

// CI run number makes every published build an in-place upgrade.
val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "dev.offlineflow"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // Store id. Debug builds get ".debug" so they install beside the release.
        applicationId = "dev.hearsay"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.0-build.$buildNumber"
        // Opt-in cloud mode (bring your own key). Build with -PnoCloud to compile it out (F-Droid).
        buildConfigField("boolean", "CLOUD", if (project.hasProperty("noCloud")) "false" else "true")
        buildConfigField("boolean", "TELEMETRY", telemetry.toString())
        buildConfigField("String", "SENTRY_DSN", "\"$sentryDsn\"")
        externalNativeBuild {
            cmake { arguments += listOf("-DANDROID_STL=c++_shared", "-DSHERPA_ORT_DIR=${sherpaOrtDir.absolutePath}") }
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
        // Release key: app/signing/hearsay-release.p12.enc is decrypted in CI with the
        // HEARSAY_SIGNING_PASSWORD secret (to HEARSAY_KEYSTORE). Without it, release builds are unsigned.
        val ks = System.getenv("HEARSAY_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
        if (ks != null) create("release") {
            storeFile = ks
            storeType = "pkcs12"
            storePassword = System.getenv("HEARSAY_SIGNING_PASSWORD")
            keyAlias = "hearsay"
            keyPassword = System.getenv("HEARSAY_SIGNING_PASSWORD")
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // x86_64 only so the CI emulator tour can run it.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Phones only: every supported phone (Android 10+, enough RAM for the models) is 64-bit ARM.
            ndk { abiFilters += listOf("arm64-v8a") }
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
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
            // AGP also offers the CMake-imported copy of sherpa's ONNX Runtime (byte-identical, from the same AAR): keep one.
            pickFirsts += "**/libonnxruntime.so"
        }
    }

    buildFeatures { buildConfig = true }

    // Telemetry's Sentry backend, or its no-op twin for -PnoTelemetry builds.
    sourceSets.getByName("main").java.srcDir(if (telemetry) "src/telemetry/java" else "src/notelemetry/java")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // The sherpa-onnx AAR may be compiled with a newer Kotlin than ours.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(files(sherpaAar))
    implementation(project(":llm"))
    if (telemetry) {
        // Core (crashes, ANRs, events) + NDK (native crashes in llama.cpp, whisper.cpp, Parakeet, ONNX Runtime, LiteRT-LM).
        // Not the "sentry-android" bundle: that also pulls in session replay, which Hearsay doesn't use.
        implementation("io.sentry:sentry-android-core:8.64.0")
        implementation("io.sentry:sentry-android-ndk:8.64.0")
    }
    testImplementation("junit:junit:4.13.2")
}
