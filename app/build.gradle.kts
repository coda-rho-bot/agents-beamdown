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
        versionCode = 1
        versionName = "0.1.0"
        val apiKey = (project.findProperty("letta_api_key") as String?) ?: ""
        val envName = (project.findProperty("letta_env_name") as String?) ?: "zfold-7"
        buildConfigField("String", "LETTA_API_KEY", "\"$apiKey\"")
        buildConfigField("String", "ENV_NAME", "\"$envName\"")
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
