import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("kitejs.multiplatform")
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs is the artifact KiteJS shipped as before it had more than one engine: kitejs-api with
 * kitejs-rhino underneath. It keeps those coordinates resolving and carries the deprecated
 * `KiteJs { }` that opened Rhino without naming it. New code depends on kitejs-api and the engine
 * it wants. It goes away at 1.0.
 */
kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.kitejsApi)
            api(projects.kitejsRhino)
        }
    }
}
