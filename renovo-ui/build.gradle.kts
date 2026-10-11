plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    // Dokka goes before maven-publish: the javadoc jar is wired while the publish plugin is applied.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.roborazzi)
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

    // Bytecode level of the published classes. AGP's default is still Java 8. Java 17 matches what
    // host apps build with today and avoids javac's "obsolete" warnings.
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
            // Robolectric's Android 16 and 17 runtimes use a class inside the JDK that JDK 17 and
            // later lock away. Without this export the test JVM throws IllegalAccessException.
            all { test ->
                test.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
            }
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
    // Public API: RenovoColorSchemes takes Material 3 ColorScheme values, so a host app compiles against it.
    api(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.window)
    // Public API: Renovo.attachUi takes a ComponentActivity, so a host app compiles against the activity library.
    api(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.espresso.core)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    // Declares the activity that the Compose test rule launches. Without it Robolectric cannot find it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // The @Preview functions live in src/debug, so neither tool reaches the published release library.
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.tooling.preview)
}
