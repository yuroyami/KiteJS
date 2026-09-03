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

// :kitejs is the whole engine: lexer, parser, bytecode generator, interpreter and
// the ECMAScript runtime, all in commonMain. Ported from Mozilla Rhino 1.9.1
// (interpreter path only, no JVM bytecode compiler). kotlin-stdlib only at runtime;
// the upstream Rhino jar appears in jvmTest as a differential-testing oracle.
include(":kitejs")
