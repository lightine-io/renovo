plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // Dokka goes before maven-publish: the javadoc jar is wired while the publish plugin is applied.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    // Screenshot tests will use Roborazzi (libs.plugins.roborazzi). It is not applied yet, see the
    // note in gradle/libs.versions.toml.
}

mavenPublishing {
    coordinates(group.toString(), "renovo-ui", version.toString())

    pom {
        name.set("renovo-ui")
        description.set(
            "Renovo update UI: ready-made Jetpack Compose and Material 3 screens for the update flow " +
                "of renovo-core. Optional; a host app can build its own screens on renovo-core instead.",
        )
    }
}

kotlin {
    // A public declaration without an explicit visibility or type would widen the API by accident.
    // This makes the compiler reject it.
    explicitApi()
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "io.lightine.renovo.ui"
    compileSdk = 37

    // Bytecode level of the published classes. AGP's default is still Java 8; 17 matches the
    // toolchain the host apps of 2026 build with and avoids javac's "obsolete" warnings.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        minSdk = 24
        // Rules for the host app's code shrinker. Global options must never go in this file: since
        // AGP 9 publishing fails when consumer rules contain them.
        consumerProguardFiles("consumer-rules.pro")
    }

    // A library has no targetSdk of its own. These two set the level its tests and lint assume.
    testOptions {
        targetSdk = 37
        unitTests {
            // Robolectric tests read the merged resources, manifest, and assets.
            isIncludeAndroidResources = true
        }
    }

    lint {
        targetSdk = 37
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    // Public API: the update UI is built on renovo-core types (state, config) and exposes composables,
    // so a host app's compiler needs both.
    api(project(":renovo-core"))
    api(libs.androidx.compose.runtime)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.window)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // Declares the activity that the Compose test rule launches. Without it Robolectric cannot find it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
