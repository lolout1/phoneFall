plugins {
    // Apply the Android and Kotlin plugins at the **module** level,
    // or reference them here with `apply false`.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// Usually no top-level buildscript{} block needed in modern AGP usage

// (Optional) If you keep a repositories block here, it can mirror the ones in settings
// repositories {
//     google()
//     mavenCentral()
// }
