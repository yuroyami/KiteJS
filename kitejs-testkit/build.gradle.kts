plugins {
    id("kitejs.multiplatform")
}

/*
 * :kitejs-testkit is the contract every engine keeps: what KiteJs, the handles, the host bindings,
 * the promise helpers and the errors do, written once against kitejs-api. Each engine module
 * subclasses these suites in its own tests, so a behaviour that differs between engines fails in
 * the engine that differs, on every target that engine runs on.
 *
 * It is not published; it exists for the engine modules' tests.
 */
kotlin {
    sourceSets {
        all {
            languageSettings {
                optIn("io.github.yuroyami.kitejs.api.InternalKiteJsApi")
            }
        }

        commonMain.dependencies {
            api(projects.kitejsApi)
            api(kotlin("test"))
            api(libs.kotlinx.coroutines.test)
        }

        // A main source set gets no test framework of its own, and the suites' annotations need
        // one where they are compiled: JUnit 4, which the engines' JVM and Android tests run on.
        jvmMain.dependencies {
            api(kotlin("test-junit"))
        }

        androidMain.dependencies {
            api(kotlin("test-junit"))
        }
    }
}
