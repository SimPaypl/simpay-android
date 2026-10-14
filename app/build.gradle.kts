import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Optional credentials for running the demo against the real API.
 *
 * Kept in `local.properties` (git-ignored) instead of on screen, so the demo screen
 * stays a plain shop. When any of them is missing the demo runs fully offline.
 *
 */
val demoProperties: Provider<Properties> = providers
    .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
    .asText
    .map { text -> Properties().apply { text.reader().use { load(it) } } }
    .orElse(Properties())

fun demoProperty(name: String): String =
    demoProperties.get().getProperty(name).orEmpty()

android {
    namespace = "pl.simpay.android.demo"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "pl.simpay.android.demo"
        // The SDK itself supports 21; Compose Material requires 23, so the demo cannot
        // follow it any lower. This caps the demo, never the SDK.
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"


        buildConfigField("String", "SIMPAY_SERVICE_ID", "\"${demoProperty("simpay.serviceId")}\"")
        buildConfigField("String", "SIMPAY_API_KEY", "\"${demoProperty("simpay.apiKey")}\"")
        buildConfigField("String", "SIMPAY_PAYER_IP", "\"${demoProperty("simpay.payerIp")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":sdk"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // No test dependencies: the demo carries no tests of its own, the SDK is covered by
    // its own unit tests.
    debugImplementation(libs.androidx.compose.ui.tooling)
}
