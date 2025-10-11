plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jlleitschuh.gradle.ktlint") version "12.1.0"
}

android {
    namespace = "com.heisenberg.lux"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.heisenberg.lux"
        minSdk = 26
        targetSdk = 34
        versionCode = 7
        versionName = "0.7.0"
    }

    signingConfigs {
        create("release") {
            // Use keystore from environment variable or default locations
            val keystorePath = System.getenv("ANDROID_KEYSTORE")
                ?: "${System.getProperty("user.home")}/android.jks"
            val keystoreFile = file(keystorePath)

            if (keystoreFile.exists()) {
                println("Using keystore: $keystorePath")
                storeFile = keystoreFile
                // Try environment variables first, then Gradle properties, then defaults
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                    ?: project.findProperty("android.keystorePassword") as String?
                    ?: "android"
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                    ?: project.findProperty("android.keyAlias") as String?
                    ?: "key0"
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                    ?: project.findProperty("android.keyPassword") as String?
                    ?: storePassword // Use store password as key password if not specified
                println("Using key alias: $keyAlias")
            } else {
                println("Warning: Keystore not found at $keystorePath - release build will be unsigned")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign if keystore exists, otherwise build unsigned
            signingConfig =
                if (rootProject.file("heisenberg.keystore").exists()) {
                    signingConfigs.getByName("release")
                } else {
                    null
                }
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
        viewBinding = true
    }
}

ktlint {
    version.set("1.0.1")
    android.set(true)
    ignoreFailures.set(false)
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}

dependencies {
    // Minimal dependencies - only what's needed for CameraX and lifecycle
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.camera:camera-core:1.3.1")
    implementation("androidx.camera:camera-camera2:1.3.1")
    implementation("androidx.camera:camera-lifecycle:1.3.1")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("com.google.android.material:material:1.11.0")
}
