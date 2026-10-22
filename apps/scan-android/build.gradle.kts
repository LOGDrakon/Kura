// Le plugin Android (AGP) n'est mis sur le classpath que si un SDK est présent,
// pour que `:core` se construise sans accès à maven.google.com.
// Il doit être dans le même classloader que le plugin Kotlin (sinon kotlin-android ne le voit pas).
buildscript {
    val sdkDir = java.util.Properties().let { props ->
        val local = file("local.properties")
        if (local.isFile) local.inputStream().use { props.load(it) }
        listOfNotNull(props.getProperty("sdk.dir"), System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"))
            .firstOrNull { file(it).isDirectory }
    }
    if (sdkDir != null) {
        repositories {
            google()
            maven("https://repo1.maven.org/maven2")
        }
        dependencies {
            classpath(libs.android.gradle.plugin)
        }
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
