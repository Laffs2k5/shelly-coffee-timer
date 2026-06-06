plugins {
    id("com.android.application") version "8.9.1"
    id("org.jetbrains.kotlin.android") version "2.1.10"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
}

android {
    namespace = "com.shellycoffee.timer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.shellycoffee.timer"
        minSdk = 35
        targetSdk = 35
        versionCode = 5
        versionName = "2.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    // LocalLifecycleOwner for Compose + repeatOnLifecycle gating of the poll loop (keepalive-churn fix).
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")
    // v2: MQTT transport for the remote path (cloud EMQX over WSS). Plain Paho mqttv3
    // client (not the deprecated paho-android-service); WebSocket support is built in.
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // JVM unit tests for pure logic (connection decide/label/event-log) — run via testDebugUnitTest.
    testImplementation("junit:junit:4.13.2")
}
