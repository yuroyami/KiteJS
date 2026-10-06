import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.js.nodejs.NodeJsPlugin
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec
import org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin

/*
 * The targets every KiteJS module builds for, in one place so the engines, the API they share and
 * the modules on top cannot drift apart: Android, the four Apple targets, Linux on x64 and arm64,
 * Windows, the JVM, and JavaScript and WebAssembly in both the browser and Node.
 *
 * A module applies this, then adds what is its own: explicitApi and ABI dumps where it is
 * published, its dependencies, and anything native.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
}

// The JavaScript and WebAssembly tests run on one Node, new enough for WebAssembly stack
// switching, so a script that pauses on the web is tested on both targets.
val testNode = "26.10.0"
plugins.withType<NodeJsPlugin> { the<NodeJsEnvSpec>().version.set(testNode) }
plugins.withType<WasmNodeJsPlugin> { the<WasmNodeJsEnvSpec>().version.set(testNode) }

/** kitejs-quickjs becomes KiteJSQuickJS, kitejs becomes KiteJS: the Apple framework's name. */
val frameworkName: String = "KiteJS" + project.name.removePrefix("kitejs").split('-')
    .filter { it.isNotEmpty() }
    .joinToString("") { part -> if (part == "quickjs") "QuickJS" else part.replaceFirstChar { it.uppercase() } }

kotlin {
    jvmToolchain(21)

    // An expect class is still beta to the compiler, and the engines use a few for what each
    // platform does its own way: weak references, threads, loading native code.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    android {
        namespace = "io.github.yuroyami." + project.name.replace('-', '.')
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
            baseName = frameworkName
            isStatic = false
        }
    }

    // No framework on these: they are libraries, not Apple frameworks.
    linuxX64()
    linuxArm64()
    mingwX64()

    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    js {
        browser {
            // Karma serves the project directory over HTTP. A '#' anywhere in the absolute path
            // truncates that URL at the fragment marker, and every file 404s. If a browser test
            // fails locally with "404: /absolute/<some prefix>", that is why: move the checkout
            // to a path without a '#'.
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        nodejs {
            testTask {
                // Whole programs and, for the Rhino engine, the test262 slice: Mocha's two-second
                // default is nowhere near enough.
                useMocha { timeout = "1800s" }
            }
        }
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            testTask {
                useKarma { useChromeHeadless() }
            }
        }
        nodejs {
            testTask {
                useMocha { timeout = "1800s" }
            }
        }
        binaries.library()
    }

    jvm {
        // Bytecode an application on Java 11 can load, produced by the 21 toolchain. The
        // -Xjdk-release flag is what stops a newer standard library method slipping in.
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjdk-release=11")
        }
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.RequiresOptIn")
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
