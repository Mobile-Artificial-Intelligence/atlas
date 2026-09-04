import com.android.build.api.dsl.LibraryExtension

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

extensions.configure<LibraryExtension>("android") {
    namespace = "com.danemadsen.atlas.search"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // The indexer reads place/poi features out of the archive.
    api(project(":lib:pmtiles"))

    // PlaceDatabase extends RoomDatabase, and the app calls close() /
    // placeDao() on it — the supertype must ride along to consumers.
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.core)
}