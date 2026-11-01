import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nexus.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nexus.agent"
        // minSdk 26 (Android 8.0+): required by the pgjdbc driver, which uses
        // java.lang.invoke.MethodHandle (API 26). java.time is also native at this level.
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Android OAuth client ID is PUBLIC app identity, not a secret. The known Android
        // client registered for com.nexus.agent is the default; trusted CI may override it
        // through GOOGLE_OAUTH_CLIENT_ID. Never silently ship a blank client ID again.
        // IMPORTANT: Google also verifies the APK signing SHA-1 against this Android client.
        // Neon credentials remain external and MUST NOT be embedded in the APK.
        val knownAndroidOAuthClientId =
            "854765234963-v7boul2j5mhco0dnqgguq99ock0a1u9n.apps.googleusercontent.com"
        val googleOAuthClientId = System.getenv("GOOGLE_OAUTH_CLIENT_ID")
            ?.trim()?.takeIf { it.isNotBlank() } ?: knownAndroidOAuthClientId
        require(Regex("^[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com$")
            .matches(googleOAuthClientId)) {
            "Invalid Google Android OAuth client ID: check the build configuration"
        }
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$googleOAuthClientId\"")

        // Public PostHog project token (not a secret), supplied by CI/local environment.
        // Absent configuration keeps telemetry completely disabled.
        buildConfigField("String", "POSTHOG_PROJECT_TOKEN", "\"${System.getenv("POSTHOG_PROJECT_TOKEN") ?: ""}\"")
        buildConfigField("String", "POSTHOG_HOST", "\"${System.getenv("POSTHOG_HOST") ?: ""}\"")
    }

    // Stable test-signing identity for CI-generated APKs. The keystore + passwords are injected
    // at build time from GitHub Actions encrypted secrets (never committed), so every CI build
    // uses the SAME test certificate — enabling in-place APK upgrades. This is a dedicated TEST
    // key, distinct from any future production release key. When the secrets are absent (local
    // builds), it falls back to the default Android debug keystore so local dev is unaffected.
    val testStorePath = System.getenv("TEST_SIGNING_KEYSTORE")
    signingConfigs {
        if (!testStorePath.isNullOrBlank()) {
            create("test") {
                storeFile = File(testStorePath)
                storePassword = System.getenv("TEST_SIGNING_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TEST_SIGNING_KEY_ALIAS") ?: "nexustest"
                keyPassword = System.getenv("TEST_SIGNING_KEY_PASSWORD")
            }
        }
    }
    val testSigningConfig = signingConfigs.findByName("test")

    // Debug builds use the default Android debug keystore (auto-generated, per-developer/CI),
    // unless the stable test signing config is available (CI). A private key is intentionally
    // NOT tracked in the repo (supply-chain risk, R2/TD-002).
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            if (testSigningConfig != null) {
                signingConfig = testSigningConfig
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
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Secure storage (encrypted API key) — Phase 1C
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Phase 2A: DeepSeek networking
    // Opt-in anonymous analytics (no screen replay, no sensitive payloads).
    // Pinned to an exact version: 3.38.0 is the last release whose transitive kotlin-stdlib is
    // 1.8.x (metadata 1.8.0), readable by this project's Kotlin 1.9.24 compiler. 3.39.0+ pulls
    // kotlin-stdlib 2.1.x (metadata 2.1.0) and okio 3.11.x, which KSP/Kotlin 1.9 cannot read.
    implementation("com.posthog:posthog-android:3.38.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Phase 2B: Room persistence
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Neon (Postgres) — direct JDBC for Second Brain search + cloud persistence.
    implementation("org.postgresql:postgresql:42.7.4")

    // Google Identity Services / AuthorizationClient for Gmail read-only authorization.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")

    // Web fetch — jsoup for robust HTML → readable-text extraction.
    implementation("org.jsoup:jsoup:1.17.2")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
}
