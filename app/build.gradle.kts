import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing. The key details come from keystore.properties in the project
// root (git-ignored; see keystore.properties.example), or from environment
// variables for CI. Without either, release builds fall back to the debug key
// so they still install locally, but those APKs must not be distributed.
val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? =
    keystoreProps.getProperty(key) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "IPODIFY_STORE_FILE")

android {
    namespace = "com.ipodify.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ipodify.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = signingValue("storePassword", "IPODIFY_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "IPODIFY_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "IPODIFY_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (releaseStoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "iPodify: no keystore.properties or IPODIFY_STORE_FILE found; " +
                        "the release build is signed with the DEBUG key. Don't distribute it.",
                )
                signingConfigs.getByName("debug")
            }
        }
    }

    // Leave out the Google-encrypted dependency list from APKs. F-Droid and
    // IzzyOnDroid flag it; App Bundles for Play keep it.
    dependenciesInfo {
        includeInApk = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    // Above the BOM's version for basicMarquee and the newer foundation APIs
    // the iPod screens use (same pairing as BitChord).
    implementation("androidx.compose.foundation:foundation:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")

    // Artwork: bitmaps from the playing app's session, or URIs it shares.
    implementation("io.coil-kt.coil3:coil-compose:3.0.4")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
}
