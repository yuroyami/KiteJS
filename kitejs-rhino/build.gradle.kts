import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation

plugins {
    id("kitejs.multiplatform")
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs-rhino is the Rhino engine, ported to Kotlin: lexer, parser, IR, bytecode generator,
 * interpreter and the ECMAScript runtime, all in commonMain. Its only runtime dependencies are
 * kitejs-api, the engine-agnostic API it implements, and kotlinx-datetime for time zone rules.
 * Pure computation: no sockets, no threads, no disk, no JIT (interpreter only, which is also the
 * only design iOS allows).
 *
 * jvmTest carries the upstream Rhino jar as a differential-testing oracle: the same
 * script goes through upstream and through this port, outputs must match. The oracle
 * never ships; it exists only on the JVM test classpath.
 */
kotlin {
    explicitApi()

    // Records the public API in api/ so an accidental change to it shows up in review rather
    // than in someone's build. `./gradlew updateLegacyAbi` accepts a deliberate change.
    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    sourceSets {
        all {
            languageSettings {
                optIn("kotlin.experimental.ExperimentalNativeApi")
                optIn("io.github.yuroyami.kitejs.api.InternalKiteJsApi")
            }
        }

        commonMain.dependencies {
            // The API this engine implements, which an embedder programs against.
            api(projects.kitejsApi)
            // Time zone rules for Date. Everything else the engine computes itself, so JVM, JS,
            // iOS and Wasm cannot drift apart.
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
            // The contract every engine keeps, run here against Rhino.
            implementation(projects.kitejsTestkit)
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

    // Use compilation outputs directly. A task-provider-backed classpath also depends on the
    // test task itself, which would replay stale expectations before we can regenerate them.
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("test") as KotlinJvmCompilation
    dependsOn(compilation.compileAllTaskName)
    testClassesDirs = compilation.output.classesDirs
    classpath = compilation.output.allOutputs + compilation.runtimeDependencyFiles

    filter { includeTestsMatching("io.github.yuroyami.kitejs.rhino.Test262ParityTest") }
    systemProperty("test262.filter", providers.systemProperty("test262.filter").getOrElse(""))
    systemProperty("test262.root", providers.systemProperty("test262.root").getOrElse("../reference/test262"))
    maxHeapSize = "4g"
    // Tens of thousands of files through two engines produces a lot of output otherwise.
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

// The normal suite stays quick: parity is a dedicated task and replay is an explicit mode.
// No corpus test is reported as passing merely because a developer has not fetched the data.
if (providers.gradleProperty("test262Replay").isPresent) {
    kotlin.sourceSets.commonTest { kotlin.srcDir("src/test262Replay/kotlin") }
}
tasks.named<Test>("jvmTest") {
    exclude("**/Test262ParityTest.class")
}

/*
 * The test262 tree lives outside the build, is gitignored and is fetched by
 * tools/fetch-test262.sh. Every target needs to find it, and only the JVM has a working directory
 * worth relying on, so the absolute path is generated into a constant the common tests read.
 */
val test262Root = file(providers.gradleProperty("test262ReplayRoot").getOrElse("../reference/test262"))
val generateTest262Paths = tasks.register("generateTest262Paths") {
    val outputDir = layout.buildDirectory.dir("generated/test262/commonTest/kotlin")
    outputs.dir(outputDir)
    val rootPath = test262Root.absolutePath
    val expectationsPath = layout.buildDirectory.file("test262/expectations.txt").get().asFile.absolutePath
    inputs.property("rootPath", rootPath)
    inputs.property("expectationsPath", expectationsPath)
    doLast {
        val dir = outputDir.get().asFile
        dir.mkdirs()
        dir.resolve("Test262Paths.kt").writeText(
            """
            |/* Generated by the build. The test262 tree is fetched, not committed. */
            |package io.github.yuroyami.kitejs.rhino
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
