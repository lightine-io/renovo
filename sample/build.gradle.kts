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

// The same source builds version 1 and version 2 of the sample, so the update from 1 to 2 can be
// tested with real APKs: ./gradlew :sample:assembleDebug -PsampleVersionCode=2
val sampleVersionCode: Int =
    providers
        .gradleProperty("sampleVersionCode")
        .map { it.toIntOrNull() ?: error("-PsampleVersionCode must be a whole number, got \"$it\".") }
        .getOrElse(1)

// 1 -> "1.0", 2 -> "2.0", 7 -> "7.0": the name always shows which code was built.
val sampleVersionName = "$sampleVersionCode.0"

// 10.0.2.2 is how an Android emulator reaches the machine it runs on.
val sampleVersionUrl: String =
    providers.gradleProperty("sampleVersionUrl").getOrElse("https://10.0.2.2:8443/version.json")

// Name of a Renovo Network value. Passing it as text keeps BuildConfig free of library types.
val sampleTrustTestServer: Boolean =
    providers.gradleProperty("sampleTrustTestServer").map { it.toBoolean() }.getOrElse(false)

val sampleForcedNetwork: String =
    providers.gradleProperty("sampleForcedNetwork").getOrElse("UnmeteredOnly").also {
        require(it in setOf("Any", "UnmeteredOnly", "NotRoaming")) {
            "-PsampleForcedNetwork must be Any, UnmeteredOnly, or NotRoaming, got \"$it\"."
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
        versionCode = sampleVersionCode
        versionName = sampleVersionName

        buildConfigField("String", "VERSION_URL", "\"$sampleVersionUrl\"")
        buildConfigField("String", "FORCED_NETWORK", "\"$sampleForcedNetwork\"")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    buildTypes {
        release {
            // Code shrinking is on so the acceptance test AC-15 can show that Renovo's consumer
            // rules keep everything the system creates by name (workers, receivers).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))

            // Signing with the debug key is only for the sample's emulator tests: version 1 and
            // version 2 must carry the same certificate, or the install of the update is rejected.
            // A real app signs its release build with its own key, kept outside the repository.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Opt-in for AC-15: the shrunk release build must reach the self-signed test server. Instead
    // of a second copy of the config, the release source set borrows the debug manifest and
    // resources (the network security config and the certificate). Without the property the
    // release source set stays empty, so the release manifest has no network security config.
    if (sampleTrustTestServer) {
        sourceSets.getByName("release") {
            manifest.srcFile("src/debug/AndroidManifest.xml")
            res.directories.add("src/debug/res")
        }
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
    }
}

// The debug build (and the release build, with -PsampleTrustTestServer=true) trusts the test server
// through res/raw/test_server.pem, which is created on each machine by the test server and is never
// committed. A clean checkout, such as CI, has no such file, and the network security config still
// needs the resource to exist. So when the real certificate is missing, a throw-away placeholder is
// generated into the build folder and merged as a resource; it trusts nothing real and only keeps
// the build green. The real file in src/debug/res/raw always wins, because the placeholder is only
// written when it is absent.
val testServerCertificate = layout.projectDirectory.file("src/debug/res/raw/test_server.pem")
val placeholderCertificateDir = layout.buildDirectory.dir("generated/testServerCertificate")
val prepareTestServerCertificate =
    tasks.register<Exec>("prepareTestServerCertificate") {
        val real = testServerCertificate.asFile
        val outDir = placeholderCertificateDir.get().asFile
        val keystore = File(outDir, "placeholder.p12")
        val pem = File(outDir, "raw/test_server.pem")
        onlyIf { !real.isFile && !pem.isFile }
        outputs.dir(outDir)
        doFirst {
            pem.parentFile.mkdirs()
            keystore.delete()
            logger.warn("sample: no test server certificate at ${real.path}; using a placeholder that trusts nothing. See sample/README.md.")
        }
        val keytool = File(System.getProperty("java.home"), "bin/keytool").path
        commandLine(
            keytool, "-genkeypair", "-keystore", keystore.path, "-storetype", "PKCS12", "-storepass", "placeholder",
            "-alias", "placeholder", "-keyalg", "RSA", "-keysize", "2048", "-validity", "1",
            "-dname", "CN=renovo sample placeholder, O=placeholder",
        )
        doLast {
            ProcessBuilder(
                keytool, "-exportcert", "-rfc", "-keystore", keystore.path, "-storetype", "PKCS12",
                "-storepass", "placeholder", "-alias", "placeholder", "-file", pem.path,
            ).inheritIO().start().waitFor().let { code -> check(code == 0) { "keytool -exportcert failed with $code" } }
            keystore.delete()
        }
    }
android.sourceSets.getByName("debug").res.srcDir(placeholderCertificateDir.get().asFile)
if (sampleTrustTestServer) {
    android.sourceSets.getByName("release").res.srcDir(placeholderCertificateDir.get().asFile)
}
tasks
    .matching { it.name == "preDebugBuild" || (sampleTrustTestServer && it.name == "preReleaseBuild") }
    .configureEach { dependsOn(prepareTestServerCertificate) }

dependencies {
    implementation(project(":renovo-ui"))
    // AppCompatActivity is one of the host-app activity types the update UI must support.
    implementation(libs.androidx.appcompat)

    // renovo-ui exposes only runtime, material3, and activity-compose to its users.
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
