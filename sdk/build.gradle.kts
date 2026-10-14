import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.dokka.javadoc)
    `maven-publish`
    signing
}

group = "pl.simpay"
version = "1.0.0"

android {
    namespace = "pl.simpay.android"
    resourcePrefix = "simpay_"

    /*
     * Kept as low as the dependencies allow, because the AAR metadata forces every
     * integrating application to compile against at least this level. Raising it is a
     * breaking change for them, not a detail of this build.
     *
     * 34 is the floor set by fragment, browser and appcompat (minCompileSdk=34). Going
     * lower would mean downgrading those, losing their fixes for no real gain.
     */
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        /*
         * 21 is the floor of AndroidX itself: fragment 1.8.9 and appcompat 1.7.1 declare
         * minSdkVersion 21. Verified rather than assumed - `lintRelease` reports no
         * NewApi at this level, and the single place needing a newer API
         * (AmountFormatter) guards it with SDK_INT.
         *
         * Material is pinned to 1.12.0 for this: 1.14.0 requires 23 and drags in a core
         * that demands compileSdk 35. Both versions are free of known CVEs.
         */
        minSdk = 21

    }

    lint {
        /*
         * A string that exists only in a translation crashes on every other locale with
         * ResourceNotFoundException, and nothing else catches it: R carries the id, so
         * the Kotlin compiler is happy and assembleRelease succeeds. Lint is the only
         * gate, so these two are fatal.
         */
        fatal += setOf("ExtraTranslation", "MissingTranslation")

        // Dependency version advice is useful, but it must not decide whether a release
        // builds; it changes with the calendar rather than with the code.
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "UseTomlInstead",
            "UseKtx"
        )

        abortOnError = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // Only the release variant is published. Sources travel with it so integrators can
    // step into the SDK while debugging their own checkout.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

/*
 * Maven Central requires a javadoc artifact. The KDoc already in the sources is the
 * single source of truth, so Dokka renders it rather than it being written twice.
 *
 * The source set is declared by hand because AGP compiles Kotlin itself, so there is
 * no Kotlin Gradle Plugin for Dokka to read the layout from.
 */
dokka {
    dokkaSourceSets.register("main") {
        sourceRoots.from(file("src/main/java"))
        jdkVersion = 11
        // Only the published surface is documented; internal classes stay out.
        documentedVisibilities = setOf(VisibilityModifier.Public, VisibilityModifier.Protected)
    }
}

val javadocJar by tasks.registering(Jar::class) {
    archiveClassifier = "javadoc"
    from(tasks.named("dokkaGeneratePublicationJavadoc"))
}

val projectUrl = "https://github.com/SimPaypl/simpay-android"

publishing {
    val mavenUrl = providers.gradleProperty("mavenUrl")
    if (mavenUrl.isPresent) {
        repositories {
            maven {
                name = "central"
                url = uri(mavenUrl.get())
                credentials {
                    username = providers.gradleProperty("mavenUsername").orNull
                    password = providers.gradleProperty("mavenPassword").orNull
                }
            }
        }
    }

    publications {
        register<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }

            artifact(javadocJar)

            artifactId = "checkout-android"

            pom {
                name = "SimPay Checkout Android SDK"
                description = "Drop-in payment sheet for SimPay transactions on Android."
                url = projectUrl
                inceptionYear = "2026"

                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/license/mit"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "simpay"
                        name = "SimPay"
                        url = "https://simpay.pl"
                    }
                }
                scm {
                    url = projectUrl
                    connection = "scm:git:$projectUrl.git"
                    developerConnection = "scm:git:ssh://git@github.com/SimPaypl/simpay-android.git"
                }
            }
        }
    }
}

// Maven Central rejects unsigned artifacts, but a local publication needs no key, so
// signing switches itself on only once a key is configured. CI has no keyring file, so
// it passes the armoured key itself; a workstation keeps using its gpg agent.
signing {
    val inMemoryKey = providers.gradleProperty("signingInMemoryKey")
    val inMemoryKeyPassword = providers.gradleProperty("signingInMemoryKeyPassword")

    isRequired = inMemoryKey.isPresent || providers.gradleProperty("signing.keyId").isPresent

    if (inMemoryKey.isPresent) {
        useInMemoryPgpKeys(inMemoryKey.get(), inMemoryKeyPassword.orNull)
    }
    if (isRequired) {
        sign(publishing.publications)
    }
}


dependencies {
    implementation(libs.androidx.appcompat)
    implementation("androidx.browser:browser:1.8.0")
    implementation(libs.androidx.core.ktx)
    api("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation(libs.material)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
