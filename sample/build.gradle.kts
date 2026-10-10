plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

android {
    namespace = "io.lightine.renovo.sample"
    compileSdk = 37

    // Bytecode level of the published classes. AGP's default is still Java 8; 17 matches the
    // toolchain the host apps of 2026 build with and avoids javac's "obsolete" warnings.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "io.lightine.renovo.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

dependencies {
    implementation(project(":renovo-ui"))
    // AppCompatActivity is one of the host-app activity types the update UI must support.
    implementation(libs.androidx.appcompat)
}
