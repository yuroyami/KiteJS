import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
    explicitApi()

    jvmToolchain(21)

    // WeakRef is an expect class, which the compiler still calls beta. It is the engine's only
    // expect/actual and there is no weak reference in the common standard library.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // Records the public API in api/ so an accidental change to it shows up in review rather
    // than in someone's build. `./gradlew updateLegacyAbi` accepts a deliberate change.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    android {
        namespace = "io.github.yuroyami.kitejs"
        compileSdk = 36
        minSdk = 21
    }

    listOf(
        iosSimulatorArm64(),
        iosArm64(),
        iosX64(),
        macosArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "KiteJS"
            isStatic = false
        }
    }

    // No framework on these: they are libraries, not Apple frameworks. They share nativeMain
    // with the Apple targets, so they need no code of their own.
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
                // The eval corpus runs whole programs and the test262 slice runs fifty thousand
                // of them, so Mocha's two-second default is nowhere near enough on Node.
                useMocha { timeout = "1800s" }
            }
        }
        binaries.library()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            // The library is built for the browser so it can be used there. Its tests are not
            // run there: they are the same tests Node already runs, and a browser run needs a
            // browser installed on whatever machine is building.
            testTask { enabled = false }
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
                optIn("kotlin.experimental.ExperimentalNativeApi")
            }
        }

        commonMain.dependencies {
            // The only runtime dependency: time zone rules for Date. Everything else the engine
            // computes itself, so JVM, JS, iOS and Wasm cannot drift apart.
            implementation(libs.kotlinx.datetime)
        }

        // kotlinx-datetime on Kotlin/JS and Kotlin/Wasm ships no zone database of its own. Without
        // this the engine only knows UTC and fixed offsets, which DateZoneSliceTest catches at once.
        jsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }

        wasmJsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // Reading the test262 tree on every target. Test scope only: the engine has no file access.
            implementation(libs.kotlinx.io.core)
        }

        jvmTest.dependencies {
            implementation(libs.rhino.oracle)
        }
    }
}

/*
 * The test262 parity run: every file the suite has, through upstream Rhino and through this port,
 * comparing outcomes. It takes minutes and needs `tools/fetch-test262.sh` to have run, so it is
 * its own task rather than part of `check`.
 *
 * `./gradlew test262Parity` runs it all; add `-Dtest262.filter=built-ins/Array` to narrow it.
 */
val test262Parity = tasks.register<Test>("test262Parity") {
    group = "verification"
    description = "Runs test262 through both engines and reports where they disagree."

    val jvmTest = tasks.named<Test>("jvmTest")
    dependsOn(jvmTest.map { it.dependsOn })
    testClassesDirs = files(jvmTest.map { it.testClassesDirs })
    classpath = files(jvmTest.map { it.classpath })

    filter { includeTestsMatching("io.github.yuroyami.kitejs.Test262ParityTest") }
    systemProperty("test262.filter", providers.systemProperty("test262.filter").getOrElse(""))
    maxHeapSize = "4g"
    // Tens of thousands of files through two engines produces a lot of output otherwise.
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

// The normal suite stays quick: the parity run is opt in.
tasks.named<Test>("jvmTest") {
    exclude("**/Test262ParityTest.class")
}

/*
 * The test262 tree lives outside the build, is gitignored and is fetched by
 * tools/fetch-test262.sh. Every target needs to find it, and only the JVM has a working directory
 * worth relying on, so the absolute path is generated into a constant the common tests read.
 */
val test262Root = layout.projectDirectory.dir("../reference/test262").asFile
val generateTest262Paths = tasks.register("generateTest262Paths") {
    val outputDir = layout.buildDirectory.dir("generated/test262/commonTest/kotlin")
    outputs.dir(outputDir)
    val rootPath = test262Root.absolutePath
    val expectationsPath = layout.buildDirectory.file("test262/expectations.txt").get().asFile.absolutePath
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("Test262Paths.kt").writeText(
            """
            |/* Generated by the build. The test262 tree is fetched, not committed. */
            |package io.github.yuroyami.kitejs
            |
            |/** Where tools/fetch-test262.sh put the suite, as an absolute path. */
            |internal const val TEST262_ROOT: String = ${'"'}${'"'}${'"'}$rootPath${'"'}${'"'}${'"'}
            |
            |/** Where the JVM parity run left the outcomes the other targets have to match. */
            |internal const val TEST262_EXPECTATIONS: String = ${'"'}${'"'}${'"'}$expectationsPath${'"'}${'"'}${'"'}
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets.commonTest {
    kotlin.srcDir(generateTest262Paths)
}
