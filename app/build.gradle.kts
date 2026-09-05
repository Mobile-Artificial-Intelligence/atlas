import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction
import java.util.Properties

// Release signing, same scheme as maise: a local (gitignored) key.properties
// at the repo root carries the upload keystore's location and passwords; CI
// writes the same file from the org's ANDROID_* secrets (see
// .github/workflows/build.yml). Missing or incomplete properties leave the
// release build unsigned rather than pretending to be signed.
val keystore_properties = Properties().apply {
    val keystore_file = rootProject.file("key.properties")
    if (keystore_file.exists()) keystore_file.inputStream().use { load(it) }
}
val release_store_file = keystore_properties.getProperty("storeFile")
val release_store_password = keystore_properties.getProperty("storePassword")
val release_alias = keystore_properties.getProperty("releaseAlias")
val release_key_password = keystore_properties.getProperty("releasePassword")
val release_signing_complete =
    !release_store_file.isNullOrBlank() &&
        !release_store_password.isNullOrBlank() &&
        !release_alias.isNullOrBlank() &&
        !release_key_password.isNullOrBlank()
if (!release_signing_complete) {
    logger.warn("key.properties missing or incomplete; release builds will be unsigned. " +
        "See app/build.gradle.kts for the expected fields.")
}

// versionCode = commits reachable from HEAD, so every pushed change ships a
// higher versionCode than the last without hand-editing (same as maise).
// CI checks out with fetch-depth 0 to keep the count accurate.
val git_commit_count = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.danemadsen.atlas"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.danemadsen.atlas"
        minSdk = 26
        targetSdk = 36
        versionCode = git_commit_count
        versionName = "0.1.0"
    }

    signingConfigs {
        create("release") {
            // storeFile resolves relative to the app module, matching CI's
            // app/key.jks; the local file usually carries an absolute path.
            if (release_signing_complete) {
                storeFile = file(release_store_file!!)
                storePassword = release_store_password
                keyAlias = release_alias
                keyPassword = release_key_password
            }
        }
    }

    buildTypes {
        release {
            // Only sign when key.properties was complete — an unsigned
            // release output is honest; a half-configured one is not.
            if (release_signing_complete) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.viewmodel.savedstate)

    implementation(libs.maplibre.android)

    implementation(project(":lib:pmtiles"))
    implementation(project(":lib:map-style"))
    implementation(project(":lib:search"))

    implementation(libs.coroutines.android)

    // ---- beerouter (integrated source, see beerouter/VENDORING.md) ----
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.io.core)
    implementation(libs.spatialk.geojson)
    implementation(libs.spatialk.turf)
    implementation(libs.spatialk.units)
    implementation(libs.androidx.collection)
    // The generator's PBF parsing (test fixtures + upstream parity tooling)
    implementation("org.openstreetmap.osmosis:osmosis-osm-binary:0.48.3")

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
}

/** Fails the build if the merged manifest still requests network access —
 *  the hard guarantee that Atlas never gains INTERNET back via some
 *  dependency's manifest. */
abstract class NoNetworkPermissionCheck : DefaultTask() {
    @get:InputFile
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val manifest_text = mergedManifest.get().asFile.readText()
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
        ).filter { manifest_text.contains(it) }
        check(forbidden.isEmpty()) {
            "Atlas must be fully offline, but the merged manifest requests: $forbidden. " +
                "Add uses-permission tools:node=\"remove\" entries in the app manifest."
        }
    }
}

// A full-bucket pipeline run (two cutters + linker over a metro rectangle)
// holds the whole node set in memory; the Gradle default 512m cannot.
// 8g + a heap dump on OOM: the full-fixture Melbourne diagnostic has OOMed
// at 4g during the stitch index build, and the dump (plus the cutter's
// file instrumentation at /tmp/atlas-cutter-dbg.log) is what identifies the
// actual consumer. 16g of dev-machine RAM makes 8g safe.
tasks.withType<Test>().configureEach {
    maxHeapSize = "8g"
    jvmArgs("-XX:+HeapDumpOnOutOfMemoryError")
    // The pipeline diagnostic streams phase stats as it goes — without
    // this, stdout only surfaces in the JUnit XML after the test exits.
    testLogging {
        showStandardStreams = true
    }
}

// Dev/CI bucket build harness (see graph-build-cli.kt): runs the SAME
// pipeline the device's :graph service runs, under a heap the caller
// controls — the default matches a typical Android largeHeap cap, so a green
// run proves the on-device build fits. `-Pbucket=all` walks the archive's
// bbox and writes manifest.json into the out dir, so the dir itself is
// what CI uploads as the prebuilt routing artifact (upload-artifact zips
// it; the app installs the artifact via adoptPrebuiltSegments and routing
// needs no on-device build). `-Pzip=<file>` additionally mints the same
// layout as a local ZIP.
//
//   ./gradlew :app:graphBuildCli \
//     -Parchive=$HOME/atlas-prototype/tmp/australia.pmtiles \
//     -Pbucket=140,-40 -Pout=/tmp/atlas-segments [-Pheap=576m]
//   ./gradlew :app:graphBuildCli \
//     -Parchive=out/atlas-australia.pmtiles -Pbucket=all \
//     -Pout=out/segments -Pzip=out/atlas-australia-routing.zip -Pheap=4g
// Dev-only route check over a built segments dir (see graph-route-cli.kt):
// proves the .rd5 the device's :graph service produces is routable through
// the stock engine. M5 acceptance: Melbourne CBD -> Geelong, car profile.
//
//   ./gradlew :app:graphRouteCli \
//     -Psegments=/tmp/atlas-segments -Pfrom=144.9631,-37.8142 \
//     -Pto=144.3608,-38.1495 [-Pprofile=car-vario]
val route_segments = providers.gradleProperty("segments")
val route_from = providers.gradleProperty("from").orElse("144.9631,-37.8142")
val route_to = providers.gradleProperty("to").orElse("144.3608,-38.1495")
val route_profile = providers.gradleProperty("profile").orElse("car-vario")
tasks.register<JavaExec>("graphRouteCli") {
    group = "atlas-dev"
    description = "Route between two points through a built .rd5 segments dir with the stock engine"
    dependsOn("compileDebugUnitTestKotlin")
    // The unit-test classpath serves the app's classes from
    // bundleDebugClassesToRuntimeJar; reading the FileCollection alone does
    // not schedule that jar, so a stale one silently shadowed fresh classes.
    dependsOn("bundleDebugClassesToRuntimeJar")
    // …and each LIBRARY module's classes from its own
    // bundleLibRuntimeToJarDebug jar. On a fresh checkout none of these
    // jars exist: without the explicit edges the JavaExec starts with
    // holes in its classpath and dies with NoClassDefFoundError on the
    // first class it touches (locally a warm build dir hides this).
    dependsOn(":lib:pmtiles:bundleAndroidMainClassesToRuntimeJar")
    dependsOn(":lib:search:bundleAndroidMainClassesToRuntimeJar")
    dependsOn(":lib:map-style:bundleLibRuntimeToJarDebug")
    mainClass = "com.danemadsen.atlas.graph.GraphRouteCli"
    maxHeapSize = "512m"
    doFirst {
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
        args(
            route_segments.orNull ?: error("-Psegments=<dir> is required"),
            route_from.get(),
            route_to.get(),
            route_profile.get(),
        )
    }
}

val graph_build_archive = providers.gradleProperty("archive")
val graph_build_bucket = providers.gradleProperty("bucket").orElse("140,-40")
val graph_build_out = providers.gradleProperty("out").orElse("/tmp/atlas-segments")
val graph_build_zip = providers.gradleProperty("zip")
tasks.register<JavaExec>("graphBuildCli") {
    group = "atlas-dev"
    description = "Build one (or -Pbucket=all: every) 5-degree bucket from a PMTiles archive under a bounded heap"
    // AGP 9 registers the unit-test tasks only after this script evaluates,
    // so the test classpath and args are resolved lazily in doFirst.
    dependsOn("compileDebugUnitTestKotlin")
    // See graphRouteCli: keep the runtime classes jar on the task graph.
    dependsOn("bundleDebugClassesToRuntimeJar")
    // See graphRouteCli: the libs' runtime jars too, or a fresh checkout
    // runs the JavaExec with a holed classpath (CI: NoClassDefFoundError
    // com/danemadsen/atlas/pmtiles/PmtilesReader).
    dependsOn(":lib:pmtiles:bundleAndroidMainClassesToRuntimeJar")
    dependsOn(":lib:search:bundleAndroidMainClassesToRuntimeJar")
    dependsOn(":lib:map-style:bundleLibRuntimeToJarDebug")
    mainClass = "com.danemadsen.atlas.graph.GraphBuildCli"
    maxHeapSize = providers.gradleProperty("heap").orElse("576m").get()
    doFirst {
        classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
        args(
            graph_build_archive.orNull ?: error("-Parchive=<pmtiles path> is required"),
            graph_build_bucket.get(),
            graph_build_out.get(),
        )
        // Optional: bundle the built segments as the adoptable routing ZIP.
        graph_build_zip.orNull?.let { args(it) }
    }
}

androidComponents {
    onVariants { variant ->
        val capitalized_name = variant.name.replaceFirstChar { it.uppercase() }
        val check_task = tasks.register(
            "check${capitalized_name}NoNetworkPermissions",
            NoNetworkPermissionCheck::class,
        ) {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching {
            it.name == "assemble$capitalized_name" || it.name == "bundle$capitalized_name"
        }.configureEach {
            dependsOn(check_task)
        }
    }
}