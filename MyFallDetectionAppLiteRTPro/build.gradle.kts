plugins {
    // We declare the versions of these plugins in libs.versions.toml
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}

// You can put any other top-level Gradle configurations here if needed.
// Usually, for a simple single-module app, we leave this empty or minimal.
