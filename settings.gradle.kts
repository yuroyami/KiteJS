enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "KiteJS-KMP"

// :kitejs-api is what an embedder programs against, whichever engine runs underneath. It knows
// nothing about the engines; each engine module implements it.
include(":kitejs-api")

// :kitejs-rhino is the Rhino engine: lexer, parser, bytecode generator, interpreter and
// the ECMAScript runtime, all in commonMain. Ported from Mozilla Rhino 1.9.1
// (interpreter path only, no JVM bytecode compiler). The upstream Rhino jar appears in
// jvmTest as a differential-testing oracle.
include(":kitejs-rhino")

// :kitejs-coroutines puts an engine behind suspending functions, on a dispatcher that runs one
// thing at a time. Separate so the engines keep their own dependencies small.
include(":kitejs-coroutines")
