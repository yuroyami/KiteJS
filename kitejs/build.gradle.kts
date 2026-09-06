import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs is the whole JavaScript engine in commonMain: lexer, parser, IR, bytecode
 * generator, interpreter and the ECMAScript runtime. NO external runtime deps, only
 * kotlin-stdlib, exactly like :kitearchive and :kitetorrent. Pure computation: no
 * sockets, no threads, no disk, no JIT (interpreter only, which is also the only
 * design iOS allows).
 *
 * jvmTest carries the upstream Rhino jar as a differential-testing oracle: the same
 * script goes through upstream and through this port, outputs must match. The oracle
 * never ships; it exists only on the JVM test classpath.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "io.github.yuroyami.kitejs"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteJS"
            isStatic = false
        }
    }

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js(IR) {
        browser()
        nodejs {
            testTask {
                // The eval corpus runs whole programs; Mocha's two-second default is not enough on Node.
                useMocha { timeout = "300s" }
            }
        }
        binaries.library()
    }

    jvm()

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // The only runtime dependency: time zone rules for Date. Everything else the engine
            // computes itself, so JVM, JS, iOS and Wasm cannot drift apart.
            implementation(libs.kotlinx.datetime)
        }

        // kotlinx-datetime on Kotlin/JS ships no zone database of its own. Without this the
        // engine only knows UTC and fixed offsets, which DateZoneSliceTest catches at once.
        jsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(libs.rhino.oracle)
        }
    }
}
