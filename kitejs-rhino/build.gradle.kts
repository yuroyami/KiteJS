import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest

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

// The normal suite has no corpus test. Explicit replay validates the producer's artifact before
// compiling any target; CI selects Test262SliceTest and records that target's execution count.
tasks.named<Test>("jvmTest") { exclude("**/Test262ParityTest.class") }

fun corpusCommand(vararg arguments: String) {
    val command = providers.exec {
        workingDir(rootProject.projectDir)
        val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
        commandLine(listOf(python, "tools/test262-corpus.py") + arguments)
        isIgnoreExitValue = true
    }
    logger.lifecycle(command.standardOutput.asText.get())
    logger.lifecycle(command.standardError.asText.get())
    command.result.get().assertNormalExitValue()
}

tasks.register("bundleTest262") {
    group = "verification"
    description = "Runs JVM parity and packages the exact selected corpus for other targets."
    dependsOn(test262Parity)
    doLast {
        val corpus = file(providers.systemProperty("test262.root").getOrElse("../reference/test262"))
        corpusCommand("pack", "--corpus", corpus.absolutePath)
    }
}

if (providers.gradleProperty("test262Replay").isPresent) {
    val bundle = file(providers.gradleProperty("test262Bundle").getOrElse("build/test262/corpus"))
    val kotlinOutput = layout.buildDirectory.dir("generated/test262/replay/kotlin")
    val karmaOutput = layout.buildDirectory.dir("test262/karma")
    val generateReplay = tasks.register("generateTest262Replay") {
        outputs.dirs(kotlinOutput, karmaOutput)
        // Validate against the current source revision every time, including restored artifacts.
        outputs.upToDateWhen { false }
        doLast {
            corpusCommand("generate", "--bundle", bundle.absolutePath, "--output", kotlinOutput.get().asFile.absolutePath)
            val config = karmaOutput.get().file("test262.js").asFile
            config.parentFile.mkdirs()
            // JSON supplies a JavaScript string literal, including Windows path escaping.
            val path = groovy.json.JsonOutput.toJson(bundle.absolutePath.replace('\\', '/'))
            config.writeText("""
                const test262Bundle = $path;
                config.files.push({ pattern: test262Bundle + '/**/*', included: false, served: true, watched: false });
                config.proxies = Object.assign({}, config.proxies, { '/test262/': '/absolute' + test262Bundle + '/' });
                config.browserNoActivityTimeout = 3600000;
                config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 3600000 });
            """.trimIndent())
        }
    }
    kotlin.sourceSets.commonTest {
        kotlin.srcDir("src/test262Replay/kotlin")
        kotlin.srcDir(generateReplay.map { kotlinOutput.get().asFile })
    }
    kotlin.sourceSets.matching { it.name in setOf("jvmTest", "nativeTest", "androidHostTest") }.configureEach {
        kotlin.srcDir("src/test262File/kotlin")
    }
    kotlin.sourceSets.jsTest { kotlin.srcDir("src/test262Js/kotlin") }
    kotlin.sourceSets.wasmJsTest { kotlin.srcDir("src/test262Wasm/kotlin") }
    tasks.withType<KotlinJsTest>().configureEach {
        if (name.endsWith("BrowserTest")) useKarma {
            useChromeHeadless()
            useConfigDirectory(karmaOutput.get().asFile)
        } else useMocha { timeout = "3600s" }
    }
    tasks.matching { it.name.endsWith("Test") && it.name != "test262Parity" }.configureEach {
        outputs.upToDateWhen { false }
    }
}
