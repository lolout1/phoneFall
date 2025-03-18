plugins {
    // We declare plugin versions here so that submodules can apply them.
    id("com.android.application") version "8.8.1" apply false
    id("org.jetbrains.kotlin.android") version "1.8.22" apply false
}

// No other statements needed at root level.
// Each module (:app and :wear) has its own build.gradle.kts.
