plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.tensorix.antigravityplayer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tensorix.antigravityplayer"
        minSdk = 27
        targetSdk = 34
        versionCode = 3
        versionName = "1.1.0-forensic-hardening"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384",
                    "-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }

        // YouTube extraction backend endpoints. Development defaults to the
        // local emulator loopback; production MUST be an https:// host
        // (enforced at runtime in YtApiService for release builds).
        buildConfigField("String", "DEV_YT_BASE_URL", "\"http://10.0.2.2:3000\"")
        buildConfigField("String", "PROD_YT_BASE_URL", "\"https://yt-backend.tensorix.com\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        compose = true
        prefab = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    // navigation-compose removed: app uses a tab-switch pattern; the
    // dependency was declared but no NavHost/NavController exists.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Oboe for direct hardware DAC access (16 KB page-aligned for Android 15+)
    implementation("com.google.oboe:oboe:1.9.3")

    // Media3 / ExoPlayer -> player engine + session + custom DSP
    // (media3-ui / rtsp were unused and removed)
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    // implementation("androidx.media3:media3-exoplayer-ffmpeg:1.3.1") // Requires manual JNI build for most devices
    
    // External high-performance decoder support via MediaCodec hardening
    // Phase 15: High-Precision 64-bit Audio DSP Architecture Enhancement

    // Room DB -> Phase 1 (library) & Phase 3 (YT cache).
    // Schema JSONs are exported to app/schemas for versioned migration tests.
    implementation("androidx.room:room-runtime:2.7.0-alpha13")
    implementation("androidx.room:room-ktx:2.7.0-alpha13")
    ksp("androidx.room:room-compiler:2.7.0-alpha13")

    // Networking -> LLM APIs (OkHttp only; Retrofit/Gson were unused and removed)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Security -> Phase 4 (encrypted BYOK key storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    // Real org.json for JVM tests (android.jar ships only method stubs).
    testImplementation("org.json:json:20240303")
}
