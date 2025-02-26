plugins {
    // Apply the Android and Kotlin plugins at the **module** level,
    // or reference them here with `apply false`.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}