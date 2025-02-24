plugins {
    // If you use version catalogs in libs.versions.toml:
    // alias(libs.plugins.android.application)
    // alias(libs.plugins.kotlin.android)
    // Otherwise, just do:
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.myfalldetectionapplitertpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myfalldetectionapplitertpro"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX + Material
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Google Play services LiteRT dependencies
    // (Use the com.google.ai.edge.litert group IDs, not the older org.tensorflow)
// In your app/build.gradle(.kts) dependencies:

        // ...
        // Google Play Services TFLite:
        implementation("com.google.android.gms:play-services-tflite-java:16.1.0")
        implementation("com.google.android.gms:play-services-tflite-support:16.1.0")
        // Optional GPU delegate (if needed):
        // implementation("com.google.android.gms:play-services-tflite-gpu:16.2.0")


    // (Optional) GPU support:
    // implementation("com.google.ai.edge.litert:litert-gpu-api:1.1.2")

    // Remove or exclude old TFLite references if previously added, e.g.
    // implementation("org.tensorflow:tensorflow-lite:2.xx.x") -> Remove
    // implementation("org.tensorflow:tensorflow-lite-support:0.xx.x") -> Remove
}
