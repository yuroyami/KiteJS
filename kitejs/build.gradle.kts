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
        nodejs()
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
            // Intentionally empty. KiteJS core depends on kotlin-stdlib only.
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmTest.dependencies {
            implementation(libs.rhino.oracle)
        }
    }
}
