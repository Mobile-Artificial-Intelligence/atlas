import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

extensions.configure<LibraryExtension>("android") {
    namespace = "com.danemadsen.atlas.mapstyle"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The style template ships as a library asset; sprites and glyphs live in
    // :app's assets (they are app presentation, and only used once loaded).
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
}