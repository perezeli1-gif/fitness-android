plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-parcelize")
}

android {
    namespace = "il.eliperez.fitness"
    compileSdk = 35

    defaultConfig {
        applicationId = "il.eliperez.fitness"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "4.6"
    }

    // מפתח חתימה קבוע: בלעדיו כל בנייה נחתמת במפתח אחר ואנדרואיד חוסם את העדכון
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("keystore/fitness-release.jks")
            storePassword = "lU4dLZan6C9P5rY20TIVxsZG"
            keyAlias = "fitness"
            keyPassword = "lU4dLZan6C9P5rY20TIVxsZG"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0-alpha11")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Samsung Health Data SDK: קריאה ישירה של האימונים מ-Samsung Health
    implementation(files("libs/samsung-health-data-api-1.1.0.aar"))
    implementation("com.google.code.gson:gson:2.11.0")
}
