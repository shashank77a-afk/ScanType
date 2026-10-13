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
        versionCode = 6; versionName = "1.5.0"
        buildConfigField("String", "BACKEND_URL", "\"${(project.findProperty("backendUrl") as String?) ?: ""}\"")
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${(project.findProperty("googleClientId") as String?) ?: ""}\"")
    }
    signingConfigs {
        // A fixed debug key, so Google Sign-In (which checks the app signature) keeps working for every debug APK you build.
        // It is a public debug key, NOT your release key.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
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
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.8.3")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
