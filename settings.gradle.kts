pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // The Android Gradle Plugin is published only to Google's Maven repository, not to Maven
        // Central or the Gradle Plugin Portal.
        //
        // Content-filtered to the Android and Google coordinate groups, so Google's Maven cannot
        // shadow any other group's artifacts (supply-chain hygiene: Maven Central stays the
        // default for everything else).
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
include(":sample")
include(":test-server")
