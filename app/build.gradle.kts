import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---- Version: Semantic Versioning (https://semver.org). Bump these three for a release. ----
val versionMajor = 0
val versionMinor = 1
val versionPatch = 0
val semver = "$versionMajor.$versionMinor.$versionPatch"

// ---- Release signing: read from keystore.properties at the repo root (git-ignored). ----
// Without it, assembleRelease still builds, but produces an unsigned APK.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}
val hasReleaseKey = keystorePropsFile.exists()

base {
    // APKs come out as unbarred-v0.1.0-release.apk / -debug.apk
    archivesName.set("unbarred-v$semver")
}

android {
    namespace = "io.github.trevortherrien.unbarred"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.trevortherrien.unbarred"
        minSdk = 26
        targetSdk = 35
        // Always increases with the semver: 0.1.0 → 100, 0.1.1 → 101, 1.2.3 → 10203.
        versionCode = versionMajor * 10000 + versionMinor * 100 + versionPatch
        versionName = semver
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (hasReleaseKey) {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Unit tests (JVM, no device) — run with:  ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")

    // Instrumented Compose UI tests (emulator/device) — ./gradlew connectedDebugAndroidTest
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
