import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import javax.inject.Inject

plugins {
    id("kitejs.multiplatform")
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs-quickjs is QuickJS-ng, the C engine, bound to the KiteJS API. The C sources are
 * vendored under native/quickjs with the few changes KiteJS needs (native/kitejs-quickjs.patch),
 * and native/kitejs_quickjs.c is the small surface the Kotlin side drives: an int handle per
 * value, strings as UTF-16, four callbacks into the host.
 *
 * Each platform builds that C its own way:
 *   Kotlin/Native   a static library per target from the clang Kotlin/Native ships, through cinterop
 *   JVM             a JNI library per desktop OS and CPU, cross-compiled with zig, inside the jar
 *   Android         a JNI library per ABI, from the NDK
 *   JS and Wasm     one wasm32-wasi module from zig, embedded in the Kotlin code and compiled at load
 */
kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    sourceSets {
        all {
            languageSettings {
                optIn("io.github.yuroyami.kitejs.api.InternalKiteJsApi")
                optIn("kotlin.concurrent.atomics.ExperimentalAtomicApi")
            }
        }

        commonMain.dependencies {
            api(projects.kitejsApi)
            implementation(libs.kotlinx.datetime)
        }

        jsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }

        wasmJsMain.dependencies {
            implementation(npm("@js-joda/timezone", "2.3.0"))
        }

        commonTest.dependencies {
            implementation(projects.kitejsTestkit)
        }
    }
}

// ---- The C sources ------------------------------------------------------------------------------

val nativeDir = layout.projectDirectory.dir("native")
val cSources = listOf("quickjs/quickjs.c", "quickjs/dtoa.c", "quickjs/libregexp.c", "quickjs/libunicode.c", "kitejs_quickjs.c")

/** The settings every toolchain compiles with live in native/kitejs_config.h, included first. */
val cFlags = listOf("-O2", "-include", nativeDir.file("kitejs_config.h").asFile.absolutePath)

// ---- Kotlin/Native: a static library per target, from Kotlin/Native's own clang ---------------

/** Compiles the C sources for one Kotlin/Native target and archives them, with run_konan. */
abstract class KonanStaticLibrary : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val konanTarget: Property<String>

    @get:Input
    abstract val runKonan: Property<String>

    @get:Input
    abstract val flags: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headers: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        val objects = out.resolve("obj").apply {
            deleteRecursively()
            mkdirs()
        }
        exec.exec {
            workingDir = objects
            commandLine(
                listOf(runKonan.get(), "clang", "clang", konanTarget.get(), "-c", "-fPIC") +
                    flags.get() + "-I${headers.get().asFile}" + sources.files.map { it.absolutePath },
            )
        }
        val library = out.resolve("libkitejs_quickjs.a").apply { delete() }
        exec.exec {
            commandLine(
                listOf(runKonan.get(), "llvm", "llvm-ar", "rcs", library.absolutePath) +
                    objects.listFiles()!!.filter { it.extension == "o" }.sortedBy { it.name }.map { it.absolutePath },
            )
        }
    }
}

/** run_konan from the Kotlin/Native distribution the Kotlin plugin downloaded for this host. */
val runKonanPath: Provider<String> = providers.provider {
    val dataDir = System.getenv("KONAN_DATA_DIR") ?: "${System.getProperty("user.home")}/.konan"
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "aarch64" else "x86_64"
    val host = when {
        os.contains("mac") -> "macos-$arch"
        os.contains("win") -> "windows-$arch"
        else -> "linux-$arch"
    }
    val script = if (os.contains("win")) "run_konan.bat" else "run_konan"
    "$dataDir/kotlin-native-prebuilt-$host-${libs.versions.kotlin.get()}/bin/$script"
}

kotlin.targets.withType<KotlinNativeTarget>().configureEach {
    val target = this
    val konanName = target.konanTarget.name
    val buildLibrary = tasks.register<KonanStaticLibrary>("buildQuickJs${target.name.replaceFirstChar { it.uppercase() }}") {
        group = "build"
        description = "Builds QuickJS as a static library for $konanName."
        konanTarget.set(konanName)
        runKonan.set(runKonanPath)
        flags.set(cFlags)
        sources.from(cSources.map { nativeDir.file(it) })
        headers.set(nativeDir)
        outputDir.set(layout.buildDirectory.dir("quickjs/native/$konanName"))
    }
    target.compilations.getByName("main").cinterops.create("kitejs_quickjs") {
        definitionFile.set(file("src/nativeInterop/cinterop/kitejs_quickjs.def"))
        includeDirs(nativeDir.asFile)
        extraOpts("-libraryPath", buildLibrary.get().outputDir.get().asFile.absolutePath)
    }
    tasks.named(target.compilations.getByName("main").cinterops.getByName("kitejs_quickjs").interopProcessingTaskName) {
        dependsOn(buildLibrary)
    }
}
