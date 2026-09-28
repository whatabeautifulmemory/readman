import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing: keystore.properties (local, gitignored) or KEYSTORE_* env vars (CI secrets).
// Without either, assembleRelease still works and produces an unsigned APK.
val ksProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cred(env: String, prop: String): String? = System.getenv(env) ?: ksProps.getProperty(prop)
val ksFile = cred("KEYSTORE_FILE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// Version comes from the release tag: -PversionName=1.2.3 (our CI), else the tag the checkout sits on
// (how F-Droid/IzzyOnDroid build from a tag), else a fallback. versionCode is derived so it is
// monotonic without anyone remembering to bump it: 1.2.3 → 10203.
val tagName = runCatching {
    providers.exec { commandLine("git", "describe", "--tags", "--exact-match", "HEAD"); isIgnoreExitValue = true }
        .standardOutput.asText.get().trim().takeIf { it.startsWith("v") }
}.getOrNull()
val vName = ((project.findProperty("versionName") as String?) ?: tagName ?: "0.2.0").removePrefix("v")
val vCode = (project.findProperty("versionCode") as String?)?.toInt()
    ?: (vName.split(".").map { it.takeWhile(Char::isDigit).ifEmpty { "0" }.toInt() } + listOf(0, 0, 0))
        .let { (a, b, c) -> a * 10000 + b * 100 + c }.coerceAtLeast(1)

android {
    namespace = "io.github.whatabeautifulmemory.readman"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.whatabeautifulmemory.readman"
        minSdk = 28
        targetSdk = 35
        versionCode = vCode
        versionName = vName
        resourceConfigurations += listOf("en", "ko", "ja")
    }
    signingConfigs {
        create("release") {
            if (ksFile != null) {
                storeFile = ksFile
                storePassword = cred("KEYSTORE_PASSWORD", "storePassword")
                keyAlias = cred("KEY_ALIAS", "keyAlias")
                keyPassword = cred("KEY_PASSWORD", "keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions.unitTests.isReturnDefaultValues = true
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(bom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // In-app continuous capture; LifecycleCameraController gives tap-to-focus/pinch-zoom for free.
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
