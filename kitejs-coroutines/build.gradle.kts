import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("kitejs.multiplatform")
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs-coroutines puts an engine behind suspending functions. An engine is single-threaded,
 * as JavaScript is, so this module gives each engine a thread of its own and hops every call
 * onto it. Nothing here changes what the engine does; it only decides when and where the engine
 * runs, and it works with any engine kitejs-api describes.
 *
 * It is a separate artifact so the engines keep their own dependencies small. An embedder that
 * does not use coroutines pays nothing.
 */
kotlin {
    explicitApi()

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.kitejsRhino)
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
