
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.secrets.gradle.plugin)
}

// Secrets live in local.properties (gitignored) and are injected into BuildConfig at build time.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun secret(name: String, fallback: String = ""): String =
    localProperties.getProperty(name, fallback)

android {
    namespace = "com.example.guidedogtest"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.guidedogtest"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "MAPS_API_KEY", "\"${secret("MAPS_API_KEY")}\"")

        buildConfigField("String", "GROQ_API_KEY", "\"${secret("GROQ_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY", "\"${secret("ELEVENLABS_API_KEY")}\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.maps.compose)
    implementation(libs.places)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.tensorflow.lite.task.vision)
    testImplementation(libs.junit)
    // The Android org.json is a stub on the unit-test classpath, and the route parsing is worth
    // testing on the JVM rather than only on a device.
    testImplementation("org.json:json:20260814")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("com.google.android.gms:play-services-maps:20.0.0")
    implementation("com.google.ar:core:1.33.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.8.1")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
}
