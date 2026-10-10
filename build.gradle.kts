import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import kotlinx.validation.KotlinApiBuildTask
import kotlinx.validation.KotlinApiCompareTask

plugins {
    alias(libs.plugins.spotless)
    // Declared here with `apply false` so every module can use the alias without a version and all
    // modules load one copy of each plugin.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.dokka) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.binary.compatibility.validator)
}

allprojects {
    group = "io.lightine.renovo"
}

// Binary compatibility validator 0.18.2 starts its work only when it sees the `kotlin-android` plugin,
// which AGP 9 no longer applies (Kotlin is built in). So it creates no tasks for the Android library
// modules. The block below registers its own task types by hand for those modules. test-server is a
// plain Kotlin/JVM module that the plugin would pick up, and it has no API to guard.
apiValidation {
    ignoredProjects.add("test-server")
}
val abiValidatorVersion =
    libs.versions.binary.compatibility.validator
        .get()

// Maven Central metadata shared by every module that applies the maven-publish plugin. The artifact
// name and description differ per module, so each module sets those itself.
subprojects {
    plugins.withId("com.vanniktech.maven.publish") {
        extensions.configure<MavenPublishBaseExtension> {
            // Uploads to the Central Portal. `automaticRelease = false` leaves the upload as a staged
            // deployment that a maintainer publishes by hand, so a wrong upload can be dropped and
            // redone before the coordinates become permanent.
            publishToMavenCentral(automaticRelease = false)

            // Maven Central rejects releases without a signature on every file. The property is an
            // explicit escape hatch for the local publish check, which runs where no key exists. Any
            // publish that does not pass it still signs, and fails loudly when the key is missing.
            if (!providers.gradleProperty("renovo.skipSigning").isPresent) {
                signAllPublications()
            }

            pom {
                url.set("https://github.com/lightine-io/renovo")
                inceptionYear.set("2026")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }

                developers {
                    developer {
                        id.set("askerasadov")
                        name.set("Asker Asadov")
                        email.set("asker.asadov@gmail.com")
                    }
                }

                scm {
                    url.set("https://github.com/lightine-io/renovo")
                    connection.set("scm:git:git://github.com/lightine-io/renovo.git")
                    developerConnection.set("scm:git:ssh://git@github.com/lightine-io/renovo.git")
                }

                issueManagement {
                    system.set("GitHub")
                    url.set("https://github.com/lightine-io/renovo/issues")
                }
            }
        }

        plugins.withId("com.android.library") {
            extensions.configure<MavenPublishBaseExtension> {
                // Maven Central requires a javadoc jar. Dokka 2 builds it from the HTML publication task.
                configure(
                    AndroidSingleVariantLibrary(
                        variant = "release",
                        sourcesJar = SourcesJar.Sources(),
                        javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
                    ),
                )
            }
        }
    }
}

// Guards the public API of renovo-core and renovo-ui against unplanned changes. Same tasks as the
// validator plugin would create, wired to the library jar that ends up inside the AAR:
//   apiDump  writes the baseline to <module>/api/<module>.api (commit it after an intended change)
//   apiCheck fails when the compiled API differs from the committed baseline; part of `check`
// Remove this block when the validator plugin supports AGP 9 built-in Kotlin.
subprojects {
    plugins.withId("com.android.library") {
        val moduleName = name
        val baseline = layout.projectDirectory.file("api/$moduleName.api")

        // The validator's own jar and its dependencies (ASM and the Kotlin metadata reader) run the dump.
        val abiValidatorDependencies = configurations.dependencyScope("abiValidatorDependencies")
        val abiValidatorClasspath =
            configurations.resolvable("abiValidatorClasspath") {
                extendsFrom(abiValidatorDependencies.get())
            }
        dependencies.add(
            "abiValidatorDependencies",
            "org.jetbrains.kotlinx:binary-compatibility-validator:$abiValidatorVersion",
        )

        val apiBuild =
            tasks.register<KotlinApiBuildTask>("apiBuild") {
                description = "Reads the public API from the release library jar."
                runtimeClasspath.from(abiValidatorClasspath)
                // The jar AGP packs into the AAR, so the baseline describes what a host app really gets.
                // AGP has no public accessor for it.
                inputJar.set(layout.buildDirectory.file("intermediates/aar_main_jar/release/syncReleaseLibJars/classes.jar"))
                outputApiFile.set(layout.buildDirectory.file("api/$moduleName.api"))
                dependsOn("syncReleaseLibJars")
            }

        val apiDump =
            tasks.register<Copy>("apiDump") {
                group = "other"
                description = "Writes the public API to api/$moduleName.api."
                from(apiBuild.flatMap { it.outputApiFile })
                into(layout.projectDirectory.dir("api"))
            }

        val apiCheck =
            tasks.register<KotlinApiCompareTask>("apiCheck") {
                group = "verification"
                description = "Fails when the public API differs from api/$moduleName.api."
                projectApiFile.set(baseline)
                generatedApiFile.set(apiBuild.flatMap { it.outputApiFile })
                // When both run in one build, the check must see the freshly written baseline.
                mustRunAfter(apiDump)
            }

        tasks.named("check") { dependsOn(apiCheck) }
    }
}

spotless {
    val ktlintVersion = libs.versions.ktlint.get()
    // Spotless did not apply this rule from .editorconfig (checked with 8.10.4), so it is set here as well.
    // Composable functions that emit UI are named in PascalCase by Compose convention.
    val ktlintOverrides = mapOf("ktlint_function_naming_ignore_when_annotated_with" to "Composable")
    // The patterns name the source folders instead of using `**/*.kt` plus an exclude. A broad pattern
    // makes Spotless walk every build folder, and `clean build` in one run then fails while the
    // folder is being deleted.
    kotlin {
        target("*/src/**/*.kt")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
    kotlinGradle {
        target("*.gradle.kts", "*/*.gradle.kts")
        ktlint(ktlintVersion).editorConfigOverride(ktlintOverrides)
    }
}
