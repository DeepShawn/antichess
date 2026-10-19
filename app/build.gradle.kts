plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android { namespace = "com.deep shawn.antichess".replace(" ", "") ; compileSdk = 35
    defaultConfig { applicationId = "com.deep shawn.antichess".replace(" ", ""); minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "1.1.6"; ndk { abiFilters += "arm64-v8a" } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    packaging { jniLibs.useLegacyPackaging = true }
}

dependencies { implementation("androidx.core:core-ktx:1.15.0"); implementation("androidx.appcompat:appcompat:1.7.0"); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0") }
