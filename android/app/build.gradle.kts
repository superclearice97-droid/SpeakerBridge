plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A permanent signing key, when the build is given one (see scripts/make-signing-key.sh), so each
// new build installs over the last. Without it, Android's throwaway debug key is used.
val signingKeystore: String? = System.getenv("SB_KEYSTORE")?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.speakerbridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.speakerbridge"
        minSdk = 26
        targetSdk = 35
        // The CI run number, so every build counts as newer than the one before.
        versionCode = System.getenv("SB_BUILD")?.toIntOrNull() ?: 1
        versionName = "0.5." + (System.getenv("SB_BUILD")?.toIntOrNull() ?: 0)
    }

    signingConfigs {
        if (signingKeystore != null) create("permanent") {
            storeFile = file(signingKeystore)
            storePassword = System.getenv("SB_KEYSTORE_PASSWORD")
            keyAlias = "speakerbridge"
            keyPassword = System.getenv("SB_KEYSTORE_PASSWORD")
        }
    }

    // The shared compatibility database is bundled straight from the repo,
    // so Linux and Android always classify devices the same way.
    sourceSets["main"].assets.srcDirs("../../shared/compatibility")

    buildTypes {
        debug {
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("permanent")
        }
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; this is the real one (public domain, tests only).
    testImplementation("org.json:json:20240303")
}
