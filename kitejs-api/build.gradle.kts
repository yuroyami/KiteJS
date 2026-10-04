import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("kitejs.multiplatform")
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
}

/*
 * :kitejs-api is what an embedder programs against, whichever engine runs underneath: build a
 * KiteJs from an engine, bind what a script may reach, run the script, read the answer. It knows
 * nothing about the engines themselves; :kitejs-rhino and :kitejs-quickjs implement it.
 *
 * Its one dependency is kotlinx-datetime, for the time zone a Date reads local time in.
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
                optIn("io.github.yuroyami.kitejs.api.InternalKiteJsApi")
            }
        }

        commonMain.dependencies {
            api(libs.kotlinx.datetime)
        }
    }
}
