plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.angussoftware.letta.env"
    // compileSdk 36: theming-compose 0.14.1 transitives (activity-compose
    // 1.13.0 → core 1.18.0) require it. Compile-time API surface only —
    // targetSdk stays 28 (legacy external storage is intentional).
    compileSdk = 36

    defaultConfig {
        applicationId = "com.angussoftware.letta.env"
        minSdk = 26
        targetSdk = 28
        versionCode = 12
        versionName = "0.3.2"

        // Self-updater feed auth: the feed is the PUBLIC releases mirror
        // (coda/letta-environment-releases) — unauthenticated fetch is the
        // release default. An optional read token can still be baked in at
        // build time for debug builds pointed at private feeds:
        //   ./gradlew -PupdateFeedToken=... :app:assembleDebug
        // Empty (default) = unauthenticated request. Build input only —
        // never commit.
        buildConfigField(
            "String",
            "UPDATE_FEED_TOKEN",
            "\"${project.findProperty("updateFeedToken") ?: ""}\""
        )
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
        noCompress += listOf("tar", "gz", "git-elf", "so")
    }

    lint {
        // targetSdk 28 is INTENTIONAL for this app: it's a system-level
        // loader (proot/termux-style environment) that needs legacy external
        // storage semantics — see the compileSdk comment above. The
        // ExpiredTargetSdkVersion check fails lintVitalRelease on it
        // (release pipeline #11); the target is documented, not stale.
        disable += "ExpiredTargetSdkVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    // Angus Software design-system tokens (EnvPalette bridge). The token
    // vals are typed androidx.compose.ui.graphics.Color, so compose-ui is
    // needed on the COMPILE classpath (the AAR only scopes it runtime).
    // The rest of Compose stays an unused runtime transitive.
    implementation("com.angussoftware.theming:theming-compose:0.14.1")
    implementation("org.jetbrains.compose.ui:ui:1.11.1")

    testImplementation("junit:junit:4.13.2")
    // org.json is stubbed in the android.jar used by LOCAL unit tests (every
    // method throws "not mocked") — the real implementation is test-only;
    // on-device the framework provides it.
    testImplementation("org.json:json:20240303")
}
