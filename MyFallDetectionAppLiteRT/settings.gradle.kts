pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    versionCatalogs {
        create("libs") {
            // We'll reference versions in gradle/libs.versions.toml
            from(files("gradle/libs.versions.toml"))
        }
    }
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyFallDetectionAppLiteRT"
include(":app")
