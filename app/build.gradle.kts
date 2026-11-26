import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
// Optional Google Maps key for your own debug builds: MAPS_API_KEY=... in local.properties (git-ignored, never
// committed). Release builds never carry it, and anyone can paste their own key in the app's Setup instead.
val mapsApiKey: String = localProps.getProperty("MAPS_API_KEY")?.trim() ?: ""

android {
    namespace = "com.cardash.dashboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cardash.dashboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        }
        release {
            buildConfigField("String", "MAPS_API_KEY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.navigation:navigation-compose:2.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation("com.google.android.gms:play-services-maps:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    // QR code for the ride replay page link (Setup)
    implementation("com.google.zxing:core:3.5.3") // ride replay web page served from the phone
    implementation("com.google.accompanist:accompanist-permissions:0.36.0")

    // CameraX — dashcam preview + video recording
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-effects:$camerax")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // Voice assistant: Claude (Anthropic Java SDK) + encrypted storage for the user's API key
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("androidx.security:security-crypto:1.0.0")

    // Offline, on-device wake word + hands-free commands (Vosk). Android needs JNA as an AAR.
    implementation("com.alphacephei:vosk-android:0.3.47") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
