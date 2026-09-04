import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

extensions.configure<LibraryExtension>("android") {
    namespace = "com.danemadsen.atlas.pmtiles"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Directory/streaming internals are pure JVM; the module must still be an
    // Android library so :app and :lib:graph can consume it on device.
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}