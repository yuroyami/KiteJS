import com.android.build.api.withAndroid
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.konan.target.HostManager
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPOutputStream
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
// ---- The C sources ------------------------------------------------------------------------------

val nativeDir = layout.projectDirectory.dir("native")
val cSources = listOf("quickjs/quickjs.c", "quickjs/dtoa.c", "quickjs/libregexp.c", "quickjs/libunicode.c", "kitejs_quickjs.c")

/** The settings every toolchain compiles with live in native/kitejs_config.h, included first. */
val cFlags = listOf("-O2", "-include", nativeDir.file("kitejs_config.h").asFile.absolutePath)

/** Every header under native, kitejs_config.h among them. */
val cHeaders = fileTree(nativeDir) { include("**/*.h") }

// ---- The JVM: a JNI library per desktop platform, cross-compiled with zig ---------------------

/** Builds with `zig cc`, which cross-compiles to every desktop OS and to WebAssembly from any of them. */
abstract class ZigCompile : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val zig: Property<String>

    @get:Input
    abstract val target: Property<String>

    @get:Input
    abstract val flags: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The headers the sources include, so that a change to one builds the library again. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headers: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun build() {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        exec.exec {
            commandLine(
                listOf(zig.get(), "cc", "-target", target.get(), "-g0", "-s", "-o", out.absolutePath) +
                    flags.get() + sources.files.map { it.absolutePath },
            )
        }
        // zig leaves the import library and debug data next to a DLL; only the library is wanted.
        out.parentFile.listFiles()!!.filter { it != out }.forEach { it.delete() }
    }
}

/** zig from the kitejs.zig property, the ZIG environment variable, or the PATH. */
val zigPath: Provider<String> = providers.gradleProperty("kitejs.zig")
    .orElse(providers.environmentVariable("ZIG"))
    .orElse("zig")

/** jni.h from the JDK the build runs on; native/jni/include supplies jni_md.h for every platform. */
val jdkInclude: Provider<String> = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}.map { it.metadata.installationPath.dir("include").asFile.absolutePath }

/** The desktop platforms the jar carries a library for: resource folder, zig target, file name. */
val jniPlatforms = listOf(
    Triple("linux-x86_64", "x86_64-linux-gnu.2.17", "libkitejs_quickjs.so"),
    Triple("linux-aarch64", "aarch64-linux-gnu.2.17", "libkitejs_quickjs.so"),
    Triple("macos-x86_64", "x86_64-macos", "libkitejs_quickjs.dylib"),
    Triple("macos-aarch64", "aarch64-macos", "libkitejs_quickjs.dylib"),
    Triple("windows-x86_64", "x86_64-windows-gnu", "kitejs_quickjs.dll"),
)

val jniOutput = layout.buildDirectory.dir("quickjs/jni")

val jniTasks = jniPlatforms.map { (platform, zigTarget, fileName) ->
    tasks.register<ZigCompile>("buildQuickJsJni" + platform.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }) {
        group = "build"
        description = "Builds the QuickJS JNI library for $platform with zig."
        zig.set(zigPath)
        target.set(zigTarget)
        flags.set(
            jdkInclude.map { include ->
                listOf("-shared", "-fPIC", "-fvisibility=hidden") + cFlags + listOf("-I$include", "-I${nativeDir.dir("jni/include").asFile}", "-I${nativeDir.asFile}") +
                    (if (platform.startsWith("linux")) listOf("-lm") else emptyList())
            },
        )
        sources.from(cSources.map { nativeDir.file(it) } + nativeDir.file("jni/kitejs_quickjs_jni.c"))
        headers.from(cHeaders)
        output.set(jniOutput.map { it.file("jni/$platform/$fileName") })
    }
}

/** Every desktop JNI library, laid out as the jar's resources. */
val buildJni = tasks.register("buildQuickJsJni") {
    group = "build"
    description = "Builds the QuickJS JNI library for every desktop platform."
    dependsOn(jniTasks)
    outputs.dir(jniOutput)
}

// ---- Android: a JNI library per ABI, from the NDK's clang -------------------------------------

/** Builds one shared library with a C compiler given as a path, here the NDK's clang. */
abstract class ClangCompile : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    @get:Input
    abstract val clang: Property<String>

    @get:Input
    abstract val flags: ListProperty<String>

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The headers the sources include, so that a change to one builds the library again. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val headers: ConfigurableFileCollection

    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun build() {
        val out = output.get().asFile
        out.parentFile.mkdirs()
        exec.exec {
            commandLine(listOf(clang.get(), "-o", out.absolutePath) + flags.get() + sources.files.map { it.absolutePath })
        }
    }
}

/**
 * The NDK from the kitejs.ndk property or the environment, or the newest one in the SDK. Only
 * the Android build reads it, so a build that never packages Android needs no NDK.
 */
val ndkClang: Provider<String> = providers.provider {
    val ndk = listOfNotNull(
        providers.gradleProperty("kitejs.ndk").orNull,
        System.getenv("ANDROID_NDK_HOME"),
        System.getenv("ANDROID_NDK_LATEST_HOME"),
        System.getenv("ANDROID_NDK_ROOT"),
    ).firstOrNull { it.isNotBlank() }?.let(::File)
        ?: listOfNotNull(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .map { File(it, "ndk") }
            .flatMap { it.listFiles()?.toList().orEmpty() }
            .maxByOrNull { it.name.substringBefore('.').toIntOrNull() ?: 0 }
        ?: throw GradleException("Building QuickJS for Android needs the NDK: set ANDROID_NDK_HOME or -Pkitejs.ndk=<path>")
    val os = System.getProperty("os.name").lowercase()
    val host = when {
        os.contains("mac") -> "darwin-x86_64"
        os.contains("win") -> "windows-x86_64"
        else -> "linux-x86_64"
    }
    ndk.resolve("toolchains/llvm/prebuilt/$host/bin/clang" + if (os.contains("win")) ".exe" else "").absolutePath
}

/** The ABIs the AAR carries, with the clang target for each at the module's minSdk. */
val androidAbis = listOf(
    "arm64-v8a" to "aarch64-linux-android21",
    "armeabi-v7a" to "armv7a-linux-androideabi21",
    "x86_64" to "x86_64-linux-android21",
    "x86" to "i686-linux-android21",
)

val androidJniOutput = layout.buildDirectory.dir("quickjs/android")

val androidJniTasks = androidAbis.map { (abi, triple) ->
    tasks.register<ClangCompile>("buildQuickJsAndroid" + abi.split('-', '_').joinToString("") { it.replaceFirstChar(Char::uppercase) }) {
        group = "build"
        description = "Builds the QuickJS JNI library for Android $abi with the NDK."
        clang.set(ndkClang)
        // 16 KB pages, which newer Android devices use and Play requires of native code.
        flags.set(
            listOf("--target=$triple", "-shared", "-fPIC", "-fvisibility=hidden", "-g0", "-s", "-Wl,-z,max-page-size=16384") +
                cFlags + listOf("-I${nativeDir.asFile}", "-lm"),
        )
        sources.from(cSources.map { nativeDir.file(it) } + nativeDir.file("jni/kitejs_quickjs_jni.c"))
        headers.from(cHeaders)
        output.set(androidJniOutput.map { it.file("$abi/libkitejs_quickjs.so") })
    }
}

/** Every Android library, laid out as a jniLibs folder: one directory per ABI. */
abstract class AndroidJniLibs : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty
}

val buildAndroidJni = tasks.register<AndroidJniLibs>("buildQuickJsAndroid") {
    group = "build"
    description = "Builds the QuickJS JNI library for every Android ABI."
    dependsOn(androidJniTasks)
    outputDir.set(androidJniOutput)
}

/**
 * Android's host tests run on the desktop JVM, where no Android library loads, so they get the
 * desktop library built for this machine through the property the loader honors.
 */
val hostJni: Provider<File> = providers.provider {
    val os = System.getProperty("os.name").lowercase()
    val arch = if (System.getProperty("os.arch") in listOf("aarch64", "arm64")) "aarch64" else "x86_64"
    val (platform, file) = when {
        os.contains("mac") -> "macos" to "libkitejs_quickjs.dylib"
        os.contains("win") -> "windows" to "kitejs_quickjs.dll"
        else -> "linux" to "libkitejs_quickjs.so"
    }
    jniOutput.get().file("jni/$platform-$arch/$file").asFile
}

tasks.withType<Test>().matching { it.name == "testAndroidHostTest" }.configureEach {
    dependsOn(buildJni)
    val library = hostJni
    doFirst { systemProperty("kitejs.quickjs.library", library.get().absolutePath) }
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(buildAndroidJni, AndroidJniLibs::outputDir)
    }
}

// ---- JS and Wasm: one WebAssembly module, embedded in the Kotlin code ---------------------------

/** Every function kitejs_quickjs.h declares, which the module exports. */
val wasmExports: List<String> = Regex("""\b(kite_[a-z_]+)\(""")
    .findAll(nativeDir.file("kitejs_quickjs.h").asFile.readText())
    .map { it.groupValues[1] }
    .filter { it != "kite_set_host" }
    .distinct()
    .toList()

/** The WebAssembly shadow stack, from kitejs_config.h, so the link and the engine's limit agree. */
val wasmStackSize: Int = Regex("""#define KITEJS_WASM_STACK_SIZE \((\d+) \* 1024 \* 1024\)""")
    .find(nativeDir.file("kitejs_config.h").asFile.readText())!!.groupValues[1].toInt() * 1024 * 1024

val buildWasm = tasks.register<ZigCompile>("buildQuickJsWasm") {
    group = "build"
    description = "Builds QuickJS as a WebAssembly module with zig."
    zig.set(zigPath)
    target.set("wasm32-wasi")
    flags.set(
        listOf("-mexec-model=reactor") + cFlags + listOf("-I${nativeDir.asFile}", "-Wl,-z,stack-size=$wasmStackSize") +
            wasmExports.map { "-Wl,--export=$it" },
    )
    sources.from(cSources.map { nativeDir.file(it) })
    headers.from(cHeaders)
    output.set(layout.buildDirectory.file("quickjs/wasm/kitejs_quickjs.wasm"))
}

/**
    headers.from(cHeaders)
 * The module, gzipped and in base64, as Kotlin source the JS and Wasm targets compile in. A string
 * constant has a size limit, so it is split into chunks the loader joins.
 */
abstract class EmbedWasm : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val wasm: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun embed() {
        val gzipped = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(wasm.get().asFile.readBytes()) }
        }.toByteArray()
        val base64 = Base64.getEncoder().encodeToString(gzipped)
        val chunks = base64.chunked(60_000).joinToString(",\n") { "    \"$it\"" }
        val dir = outputDir.get().asFile.resolve("io/github/yuroyami/kitejs/quickjs/bridge")
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("QuickJsWasm.kt").writeText(
            "/* Generated by the build from native/: QuickJS as WebAssembly, gzipped, in base64. */\n" +
                "package io.github.yuroyami.kitejs.quickjs.bridge\n\n" +
                "internal val QUICKJS_WASM: Array<String> = arrayOf(\n$chunks,\n)\n",
        )
    }
}

val embedWasm = tasks.register<EmbedWasm>("embedQuickJsWasm") {
    wasm.set(buildWasm.flatMap { it.output })
    outputDir.set(layout.buildDirectory.dir("generated/quickjs/webMain/kotlin"))
}

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
        // Apple targets build only on a Mac. Elsewhere the Kotlin plugin skips their compilations,
        // and this skips their C, so that a task over every target, such as Dokka's, still runs.
        val buildable = HostManager().isEnabled(target.konanTarget)
        onlyIf { buildable }
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

// ---- The module ---------------------------------------------------------------------------------

kotlin {
    explicitApi()

    // The default hierarchy, plus jniMain: the JNI bridge the JVM and Android share.
    applyDefaultHierarchyTemplate {
        common {
            group("jni") {
                withJvm()
                withAndroid()
            }
        }
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    android {
        optimization {
            consumerKeepRules.publish = true
            consumerKeepRules.file("consumer-rules.pro")
        }
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

        jvmMain {
            resources.srcDir(buildJni)
        }

        webMain {
            kotlin.srcDir(embedWasm)
        }
    }
}

