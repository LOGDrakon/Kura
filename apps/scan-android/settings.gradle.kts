import java.util.Properties

pluginManagement {
    repositories {
        // Maven Central via repo1 (repo.maven.apache.org limite fortement les requêtes).
        maven("https://repo1.maven.org/maven2")
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://repo1.maven.org/maven2")
        google()
    }
}

rootProject.name = "kura-scan"

include(":core")

// Le module Android n'est inclus que si un SDK Android est disponible :
// `:core` (Kotlin pur) se compile et se teste partout, y compris en CI sans SDK.
fun androidSdkAvailable(): Boolean {
    val props = Properties()
    val local = file("local.properties")
    if (local.isFile) local.inputStream().use { props.load(it) }
    val candidates = listOfNotNull(
        props.getProperty("sdk.dir"),
        System.getenv("ANDROID_HOME"),
        System.getenv("ANDROID_SDK_ROOT"),
    )
    return candidates.any { file(it).isDirectory }
}

if (androidSdkAvailable()) {
    include(":app")
} else {
    logger.lifecycle("Kura : aucun SDK Android détecté (local.properties sdk.dir / ANDROID_HOME), module :app ignoré.")
}
