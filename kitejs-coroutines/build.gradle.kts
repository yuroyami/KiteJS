import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs-coroutines puts the engine behind suspending functions. The engine itself is
 * single-threaded, as JavaScript is, so this module owns a dispatcher that runs one thing at a
 * time and hops every call onto it. Nothing here changes what the engine does; it only decides
 * when and where the engine runs.
 *
 * It is a separate artifact so :kitejs keeps its one runtime dependency. An embedder that does not
 * use coroutines pays nothing.
 */
kotlin {
    explicitApi()

    jvmToolchain(21)

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    android {
        namespace = "io.github.yuroyami.kitejs.coroutines"
        compileSdk = 36
        minSdk = 21

        // Without this the Android target compiles but never runs a test, so the whole
        // common suite goes unchecked on the one target most likely to ship it.
        withHostTestBuilder {}
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
        macosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteJSCoroutines"
            isStatic = false
        }
    }

    linuxX64()
    linuxArm64()
    mingwX64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser {
            // The library is built for the browser so it can be used there. Its tests are not
            // run there: they are the same tests Node already runs, and a browser run needs a
            // browser installed on whatever machine is building.
            testTask { enabled = false }
        }
        nodejs {
            testTask {
                useMocha { timeout = "300s" }
            }
        }
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)
    wasmJs {
        browser {
            // The library is built for the browser so it can be used there. Its tests are not
            // run there: they are the same tests Node already runs, and a browser run needs a
            // browser installed on whatever machine is building.
            testTask { enabled = false }
        }
        nodejs {
            testTask {
                useMocha { timeout = "300s" }
            }
        }
        binaries.library()
    }

    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjdk-release=11")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.kitejs)
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
