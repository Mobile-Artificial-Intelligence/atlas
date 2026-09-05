import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    // AGP is already on the plugin classpath (the app/lib modules apply
    // com.android.library), so this resolves by id without a version —
    // a second version would fail the already-on-classpath check.
    id("com.android.kotlin.multiplatform.library")
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
        namespace = "com.danemadsen.atlas.pmtiles"
        compileSdk = 37
        minSdk = 26
    }

    // A plain JVM target so the search-index CLI (and anything else that
    // runs on the host, like CI) consumes the SAME reader code the device
    // runs — the CI-minted index and the on-device one can never diverge
    // through a parallel implementation.
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit)
        }
    }
}