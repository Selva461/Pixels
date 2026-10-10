// Offline compile check of the Android app — see README.md. Not part of the app build.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

includeBuild("../../engine")
rootProject.name = "offline-typecheck"
