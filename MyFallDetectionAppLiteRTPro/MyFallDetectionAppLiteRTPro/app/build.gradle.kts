plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")

}

android {
    namespace = "com.example.myfalldetectionapplitertpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.myfalldetectionapplitertpro"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Enable Data Binding (the v2 system is now the default)
    buildFeatures {
        //noinspection DataBindingWithoutKapt
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17" // Must be one of "1.8", "11", or "17"
    }
}

dependencies {
    // AndroidX and Material
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Google Play Services TFLite dependencies
    implementation("com.google.android.gms:play-services-tflite-java:16.1.0")
    implementation("com.google.android.gms:play-services-tflite-support:16.1.0")
    implementation(libs.databinding.common)

    // (Optional GPU delegate commented out)
    // implementation("com.google.android.gms:play-services-tflite-gpu:16.2.0")

    // Data Binding annotation processor

}
