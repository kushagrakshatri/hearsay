plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    // 2.3+: LiteRT-LM's AAR carries Kotlin 2.4 metadata (needs a 2.3 or newer compiler).
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
}
