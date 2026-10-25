pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        id("com.android.library") version "9.3.1"
        id("org.jetbrains.kotlin.android") version "2.3.21"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "luximio-links-android"
