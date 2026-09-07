plugins {
    // Declared here with apply false so the publish plugin's shared build service
    // is loaded by one classloader for the whole build. Without this, applying it
    // to sibling modules makes publishAndReleaseToMavenCentral fail.
    alias(libs.plugins.vanniktech.publish).apply(false)
    alias(libs.plugins.kotlin.multiplatform).apply(false)
    alias(libs.plugins.android.kmp.library).apply(false)
    // Applied (not deferred) at the root so `dokkaGenerate` aggregates every
    // library module into one API site at build/dokka/html (deployed to /api/).
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.yuroyami"
    version = "0.0.1"
}

dependencies {
    dokka(project(":kitejs"))
    dokka(project(":kitejs-coroutines"))
}

dokka {
    moduleName.set("KiteJS")
}

// Shared Kite theme. Sources live in ../_kite-docs; ./_kite-docs/sync.sh copies
// them here, so this repo still builds standalone from a fresh clone. Run the sync
// before the first dokkaGenerate; until then the theme files do not exist here.
allprojects {
    plugins.withId("org.jetbrains.dokka") {
        extensions.configure<org.jetbrains.dokka.gradle.DokkaExtension> {
            pluginsConfiguration.html {
                customStyleSheets.from(
                    rootProject.layout.projectDirectory.file("docs/api-theme/kite.css"),
                )
                templatesDir.set(
                    rootProject.layout.projectDirectory.dir("dokka-templates"),
                )
                footerMessage.set("MPL-2.0 · KiteJS is part of the Kite family.")
            }

            // A module with a Module.md gets its description onto the aggregated
            // "all modules" landing page, which is otherwise a bare list of names.
            dokkaSourceSets.configureEach {
                val moduleDoc = layout.projectDirectory.file("Module.md")
                if (moduleDoc.asFile.exists()) {
                    includes.from(moduleDoc)
                }
            }
        }
    }
}
