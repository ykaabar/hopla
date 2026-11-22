plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.hopla.tv"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.hopla.tv"
        minSdk = 24
        targetSdk = 37
        // Release builds on GitHub pass -PversionCode / -PversionName; a local build stays 1 / "0.1".
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (findProperty("versionName") as String?) ?: "0.1"
        // Public repository whose releases the app checks for updates.
        buildConfigField("String", "UPDATE_REPO", "\"${findProperty("updateRepo")}\"")
    }
    signingConfigs {
        // GitHub builds sign with the household key passed as secrets; it is this PC's debug key, so the
        // TVs that already have Hopla accept the updates (another key would force a reinstall and lose the data).
        create("release") {
            val ks = System.getenv("HOPLA_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("HOPLA_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOPLA_KEY_ALIAS")
                keyPassword = System.getenv("HOPLA_KEY_PASSWORD")
            } else {
                initWith(getByName("debug"))
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // shortcut: the household's key is this PC's debug key (sideloaded TVs only); use a dedicated key before any store release.
            signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    // Streaming JSON reader: big provider lists must be parsed without loading the whole file.
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.2")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.2")
    testImplementation("junit:junit:4.13.2")
    // Android's org.json is a stub in local unit tests; the real one lets the Xtream parsers be tested.
    testImplementation("org.json:json:20260522")
    // Same reason for the XML pull parser used by the TV guide.
    testImplementation("net.sf.kxml:kxml2:2.3.0")
}
