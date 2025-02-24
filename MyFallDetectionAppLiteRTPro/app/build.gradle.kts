plugins {
    // Apply the plugins we declared at the top level:
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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

    // Optional: enable ViewBinding or Compose, etc.
    buildFeatures {
        viewBinding = true
    }

    // Java/Kotlin compatibility
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // AndroidX basics
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime.ktx)

    // TFLite LiteRT dependencies
    implementation(libs.litert)
    implementation(libs.litert.support)
    implementation(libs.litert.metadata)

    // Unit tests
    testImplementation(libs.junit)

    // Instrumented tests
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
}
