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

        // Google OAuth client id for the connectors (Gmail). Injected at CI build time; a client
        // id is public (not a secret). Neon connection URLs are deliberately NOT baked in — they
        // contain credentials and are user-supplied + stored encrypted at rest (Settings).
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"${System.getenv("GOOGLE_OAUTH_CLIENT_ID") ?: ""}\"")

        // AppAuth's manifest declares a redirect receiver using this placeholder.
        manifestPlaceholders["appAuthRedirectScheme"] = "com.nexus.agent"
    }

    signingConfigs {
        create("nexusDebug") {
            storeFile = rootProject.file("keystore/nexus-debug.p12")
            storePassword = "nexusdebug"
            keyAlias = "nexus"
            keyPassword = "nexusdebug"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("nexusDebug")
        }
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

    // Connectors (Google OAuth) — AppAuth (PKCE) + Custom Tabs.
    implementation("net.openid:appauth:0.11.1")
    implementation("androidx.browser:browser:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
}
