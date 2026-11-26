plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

// GitHub Actions har build par number badhata hai -> app update ho jata hai
val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
val releaseKeystore = file("release.keystore")

android {
    namespace = "com.fieldtrack.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.fieldtrack.app"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("shared") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "fieldtrack123"
                keyAlias = "fieldtrack"
                keyPassword = System.getenv("KEYSTORE_PASSWORD") ?: "fieldtrack123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseKeystore.exists())
                signingConfigs.getByName("shared") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
}
