plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.protobuf") version "0.9.3"
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.ss.azbest"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ss.azbest"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    buildTypes {
        release {
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
    }


}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    // Compose — версия исправлена (2024.01 несовместима с material3)
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Protobuf lite (совместима с protoc 3.25.x)
    implementation("com.google.protobuf:protobuf-javalite:3.25.3")

    // BLE
    implementation("no.nordicsemi.android:ble:2.7.1")
    implementation("no.nordicsemi.android:ble-ktx:2.7.1")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
