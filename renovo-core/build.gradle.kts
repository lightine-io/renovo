plugins {
    alias(libs.plugins.android.library)
    // Dokka goes before maven-publish: the javadoc jar is wired while the publish plugin is applied.
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
}

mavenPublishing {
    coordinates(group.toString(), "renovo-core", version.toString())

    pom {
        name.set("renovo-core")
        description.set(
            "Renovo core: lets an Android app that is distributed outside the Play Store check for, " +
                "download, verify, and install its own updates. No user interface.",
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
    namespace = "io.lightine.renovo"
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
    // Part of the public API: Renovo.state is a StateFlow.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    // WorkManager already puts androidx.core in every host app. Declaring it here keeps the
    // notification and package-info helpers from depending on that side effect.
    implementation(libs.androidx.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
}
