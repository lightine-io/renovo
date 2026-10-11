pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin is published only to Google's Maven repository, not to Maven
        // Central or the Gradle Plugin Portal.
        //
        // Limited to the Android and Google groups, so Google's Maven cannot serve a fake copy of
        // any other group's artifacts. Maven Central stays the source for everything else.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

plugins {
    // Downloads the JDK that jvmToolchain(21) asks for when the machine has none, so the build
    // never depends on whichever `java` happens to be first on the PATH.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    // Repositories are declared once, here. A module that adds its own would bypass the filter below.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // androidx, Compose and Google libraries resolve from Google's Maven repository. Filtered
        // to the Android and Google groups for the same reason as in pluginManagement above.
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
    }
}

rootProject.name = "renovo"

include(":renovo-core")
include(":renovo-ui")

// Modules that live only on a developer's machine, such as a private sample app, are included
// from local/settings.gradle.kts. Git ignores that folder, and nothing in the repository depends
// on it, so a clean checkout builds without it.
val localSettings = file("local/settings.gradle.kts")
if (localSettings.isFile) apply(from = localSettings)
