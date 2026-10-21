plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.deepshawn.antichess"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.deepshawn.antichess"
        minSdk = 26; targetSdk = 35
        versionCode = 7; versionName = "2.2.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    val releaseKeyPath = System.getenv("RELEASE_KEYSTORE_PATH")
    if (!releaseKeyPath.isNullOrBlank()) {
        signingConfigs {
            create("stableRelease") {
                storeFile = file(releaseKeyPath); storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS"); keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("stableRelease")
    }
    buildTypes.getByName("release") { isMinifyEnabled = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // The opening book and piece vectors are shared with the web app — one source of truth.
    sourceSets["main"].assets.srcDirs("src/main/assets", "../web/data")
    // Stockfish ships as jniLibs/arm64-v8a/libstockfish.so; legacy packaging extracts it to
    // nativeLibraryDir, the only app location Android 10+ allows to be executed.
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies {
    implementation(project(":core"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
