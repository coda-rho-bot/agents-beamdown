plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.angussoftware.letta.env"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.angussoftware.letta.env"
        minSdk = 26
        targetSdk = 28
        versionCode = 9
        versionName = "0.2.7"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Tarball assets must stay uncompressed in the APK: assets.open()
    // streams raw asset bytes, and double-compression (deflate over gzip)
    // bloats the APK and slows the one-shot staging copy. Same treatment
    // rootfs.tar relies on (uncompressed .tar).
    androidResources {
        noCompress += listOf("tar", "gz")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
