import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Compiles app/src/main, app/src/test and app/src/androidTest with the real Kotlin and Compose
// compilers, using only Maven Central: Compose Multiplatform desktop (same Compose 1.8 API as the
// app's BOM) and Robolectric's android-all (Android 15 framework classes). AndroidX libraries that
// live only on Google Maven are replaced by compile-only stand-ins in stubs/ and teststubs/.
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

val app = rootDir.resolve("../../app/src")
val composeVersion = "1.8.2"
val androidAll = "org.robolectric:android-all:15-robolectric-13954326"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

sourceSets["main"].kotlin.srcDirs(app.resolve("main/java"), file("stubs"), layout.buildDirectory.dir("generated/r"))
sourceSets["test"].kotlin.srcDirs(app.resolve("test/java"))
val androidTest by sourceSets.creating {
    kotlin.srcDirs(app.resolve("androidTest/java"), file("teststubs"))
    compileClasspath += sourceSets["main"].output + sourceSets["main"].compileClasspath
}
configurations["androidTestImplementation"].extendsFrom(configurations["implementation"])

// Google-hosted transitive dependencies of Compose desktop: not needed to type-check app code.
configurations.all {
    listOf("androidx.lifecycle", "androidx.annotation", "androidx.arch.core", "androidx.savedstate", "androidx.collection")
        .forEach { exclude(group = it) }
}

dependencies {
    implementation("com.pixels.enhancer:domain")
    implementation("org.jetbrains.compose.runtime:runtime-desktop:$composeVersion")
    implementation("org.jetbrains.compose.ui:ui-desktop:$composeVersion")
    implementation("org.jetbrains.compose.foundation:foundation-desktop:$composeVersion")
    implementation("org.jetbrains.compose.material3:material3-desktop:$composeVersion")
    implementation("org.jetbrains.compose.material:material-icons-extended-desktop:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    compileOnly(androidAll)
    testImplementation("junit:junit:4.13.2")
    testImplementation(androidAll)
    "androidTestImplementation"("junit:junit:4.13.2")
    "androidTestImplementation"("org.jetbrains.compose.ui:ui-test-junit4-desktop:$composeVersion")
    "androidTestCompileOnly"(androidAll)
}

val generateR by tasks.registering(Exec::class) {
    val strings = app.resolve("main/res/values/strings.xml")
    val output = layout.buildDirectory.file("generated/r/com/pixels/enhancer/R.kt")
    inputs.file(strings)
    inputs.dir(app.resolve("main/res/drawable"))
    outputs.file(output)
    commandLine("python3", file("generate_r.py").path, strings.path, output.get().asFile.path)
    doFirst { output.get().asFile.parentFile.mkdirs() }
}
tasks.named("compileKotlin") { dependsOn(generateR) }

tasks.test { useJUnit() }
