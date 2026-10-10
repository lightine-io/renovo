plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

application {
    mainClass.set("io.lightine.renovo.testserver.MainKt")
}

tasks.named<JavaExec>("run") {
    // Gradle runs a module's tasks from the module folder. The documented command line names folders
    // relative to the repository root (`--dir sample/server`), so the server starts from there.
    workingDir = rootProject.projectDir
}

dependencies {
    testImplementation(libs.junit)
}
