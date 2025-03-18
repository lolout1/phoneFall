plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Make sure this matches the package in WatchMainActivity.kt
    namespace = "com.example.myfalldetectionapplitertpro.watch"
    compileSdk = 34

    defaultConfig {
        // Keep application ID different from phone app
        applicationId = "com.example.myfalldetectionapplitertpro.watch"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // If you want a standalone Wear OS app:
        manifestPlaceholders["wearApp"] = "true"
    }

    buildFeatures {
        // Enable data binding if your watch code needs it
        dataBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // Add this to ensure proper resource generation
    sourceSets {
        getByName("main") {
            res.srcDirs("src/main/res")
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))

    // Wear OS
    implementation("androidx.wear:wear:1.2.0")

    // Kotlin
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.22")
    implementation("androidx.core:core-ktx:1.10.1")

    // Wearable APIs
    implementation("com.google.android.gms:play-services-wearable:18.0.0")

    // Coroutines for async tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.6.4")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")

    // Required for Wear devices
    implementation("androidx.wear:wear:1.3.0")
    implementation ("androidx.wear.watchface:watchface:1.2.1")
    implementation ("androidx.wear.watchface:watchface-complications-data-source:1.2.1")
    implementation ("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
    implementation ("androidx.wear.watchface:watchface-editor:1.2.1")
    implementation ("androidx.wear.watchface:watchface-complications-rendering:1.2.1")
    // Material library
    implementation("com.google.android.material:material:1.9.0")
    implementation("com.google.android.gms:play-services-tasks:18.0.2")
    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("com.google.android.gms:play-services-wearable:18.0.0")
    implementation("androidx.wear:wear:1.2.0")
    // For watch <-> phone messaging
    implementation("com.google.android.gms:play-services-wearable:18.0.0")
}