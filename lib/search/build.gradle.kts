import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP is already on the plugin classpath (the app/lib modules apply
    // com.android.library), so this resolves by id without a version —
    // a second version would fail the already-on-classpath check.
    id("com.android.kotlin.multiplatform.library")
    alias(libs.plugins.ksp)
}

kotlin {
    // The android target of a KMP LIBRARY is configured through this block
    // (KGP 2.4's replacement for the deprecated androidLibrary block):
    // namespace/minSdk live here, not on a top-level android block.
    android {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
        namespace = "com.danemadsen.atlas.search"
        compileSdk = 37
        minSdk = 26
    }

    // A plain JVM target so CI mints the prebuilt search index with the
    // SAME indexer, entities and Room schema the device runs: a JVM-minted
    // DB (BundledSQLiteDriver) opens on Android (framework driver) because
    // Room's identity hash is target-independent — the CI artifact can
    // never diverge from what an on-device build would have produced.
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            // The indexer reads place/poi/address features out of the archive.
            api(project(":lib:pmtiles"))

            // PlaceDatabase extends RoomDatabase, and the app calls close() /
            // placeDao() on it — the supertype must ride along to consumers.
            // (No room-ktx: its only API, withTransaction, is used nowhere,
            // and it has no JVM variant to offer this module's jvm target.)
            api(libs.room.runtime)
            implementation(libs.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
            implementation(libs.coroutines.core)
        }
        jvmMain.dependencies {
            // The bundled SQLite driver the JVM target's CLI opens the index
            // DB through (Android uses its platform driver instead).
            implementation("androidx.sqlite:sqlite-bundled-jvm:2.6.0")
        }
    }
}

dependencies {
    // Room's KSP processor runs per-target: the entities live in
    // commonMain but each target compiles its own generated implementation
    // (PlaceDatabase_Impl is target-specific bytecode).
    add("kspAndroid", libs.room.compiler)
    add("kspJvm", libs.room.compiler)
}

// CI/dev harness (see jvmMain/…/search-index-cli.kt): mints the prebuilt
// search index for one archive — the SAME indexer pass the device's import
// runs, under the same Room schema — plus the adopt gate's manifest.
//
//   ./gradlew :lib:search:searchIndexCli \
//     -Parchive=$GITHUB_WORKSPACE/out/atlas-$COUNTRY.pmtiles \
//     -Pout=$GITHUB_WORKSPACE/out/search -Pheap=4g
val search_index_archive = providers.gradleProperty("archive")
val search_index_out = providers.gradleProperty("out")
val jvm_target = kotlin.targets.getByName("jvm")
tasks.register<JavaExec>("searchIndexCli") {
    group = "atlas-dev"
    description = "Mint the prebuilt search index DB + manifest for one PMTiles archive"
    dependsOn("compileKotlinJvm")
    mainClass = "com.danemadsen.atlas.search.SearchIndexCli"
    maxHeapSize = providers.gradleProperty("heap").orElse("4g").get()
    doFirst {
        // The full runtime classpath: the compilation's own classes (there
        // are no jvmMain resources in this module — the DB is created at
        // runtime) plus its runtime dependencies.
        val jvm_main = jvm_target.compilations.getByName("main")
            as org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
        classpath = files(jvm_main.output.classesDirs) + jvm_main.runtimeDependencyFiles
        args(
            search_index_archive.orNull ?: error("-Parchive=<pmtiles path> is required"),
            search_index_out.orNull ?: error("-Pout=<dir> is required"),
        )
    }
}