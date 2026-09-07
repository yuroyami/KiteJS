import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

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
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitejs.coroutines"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteJSCoroutines"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs {
            testTask {
                useMocha { timeout = "300s" }
            }
        }
        binaries.library()
    }

    jvm()

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
