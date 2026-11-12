import java.util.Properties

/**
 * Release signing.
 *
 * A gitignored `keystore.properties` beside this file's project root, with
 * storeFile / storePassword / keyAlias / keyPassword — or the same four values
 * as environment variables (BL_KEYSTORE_FILE, BL_KEYSTORE_PASSWORD,
 * BL_KEY_ALIAS, BL_KEY_PASSWORD). With neither, `assembleRelease` still
 * succeeds and produces an unsigned APK.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(propertyKey: String, environmentKey: String): String? =
    keystoreProperties.getProperty(propertyKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentKey)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "BL_KEYSTORE_FILE")
val releaseStorePassword = signingValue("storePassword", "BL_KEYSTORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "BL_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "BL_KEY_PASSWORD")
val canSignRelease = listOf(
    releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
).all { it != null } && file(releaseStoreFile!!).exists()

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.dannycom888.mylrcauto"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dannycom888.mylrcauto"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = "1.0.11"
    }

    if (canSignRelease) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            if (canSignRelease) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.car.app)
    testImplementation(libs.junit)
}
