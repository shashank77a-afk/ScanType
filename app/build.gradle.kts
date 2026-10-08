import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val ksFile = rootProject.file("keystore.properties")
val ks = Properties().apply { if (ksFile.exists()) load(ksFile.inputStream()) }
android {
    namespace = "com.scantype.documentscanner"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.scantype.documentscanner"
        minSdk = 24; targetSdk = 35
        versionCode = 1; versionName = "1.0.0"
    }
    signingConfigs {
        if (ksFile.exists()) create("release") {
            storeFile = file(ks["storeFile"] as String)
            storePassword = ks["storePassword"] as String
            keyAlias = ks["keyAlias"] as String
            keyPassword = ks["keyPassword"] as String
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true; isShrinkResources = true; isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (ksFile.exists()) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
