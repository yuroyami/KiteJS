plugins {
    `kotlin-dsl`
}

/*
 * The Kotlin and Android plugins live here, on the classpath every module shares, so the
 * convention in src/main/kotlin can configure them and every module applies them without a
 * version of its own.
 */
dependencies {
    implementation(plugin(libs.plugins.kotlin.multiplatform))
    implementation(plugin(libs.plugins.android.kmp.library))
}

fun plugin(plugin: Provider<PluginDependency>): Provider<String> =
    plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }
