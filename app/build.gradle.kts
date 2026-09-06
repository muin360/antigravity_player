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
    compileSdk = 37

    // Release signing credentials are loaded from ~/.gradle/gradle.properties
    // to keep secrets out of VCS. Required keys:
    //   ANTIGRAVITY_RELEASE_STORE_FILE=/path/to/release.keystore
    //   ANTIGRAVITY_RELEASE_STORE_PASSWORD=...
    //   ANTIGRAVITY_RELEASE_KEY_ALIAS=...
    //   ANTIGRAVITY_RELEASE_KEY_PASSWORD=...
    signingConfigs {
        create("release") {
            val props = project.properties
            val env = System.getenv()
            val keystorePath = (props["ANTIGRAVITY_RELEASE_STORE_FILE"] as? String)
                ?: env["ANTIGRAVITY_RELEASE_STORE_FILE"]
                ?: (props["RELEASE_KEYSTORE_PATH"] as? String)
                ?: env["RELEASE_KEYSTORE_PATH"]
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = (props["ANTIGRAVITY_RELEASE_STORE_PASSWORD"] as? String)
                ?: env["ANTIGRAVITY_RELEASE_STORE_PASSWORD"]
                ?: (props["RELEASE_KEYSTORE_PASSWORD"] as? String)
                ?: env["RELEASE_KEYSTORE_PASSWORD"]
                ?: ""
            keyAlias = (props["ANTIGRAVITY_RELEASE_KEY_ALIAS"] as? String)
                ?: env["ANTIGRAVITY_RELEASE_KEY_ALIAS"]
                ?: (props["RELEASE_KEY_ALIAS"] as? String)
                ?: env["RELEASE_KEY_ALIAS"]
                ?: ""
            keyPassword = (props["ANTIGRAVITY_RELEASE_KEY_PASSWORD"] as? String)
                ?: env["ANTIGRAVITY_RELEASE_KEY_PASSWORD"]
                ?: (props["RELEASE_KEY_PASSWORD"] as? String)
                ?: env["RELEASE_KEY_PASSWORD"]
                ?: ""
        }
    }

    defaultConfig {
        applicationId = "com.tensorix.antigravityplayer"
        minSdk = 27
        targetSdk = 37
        versionCode = 4
        versionName = "2.0.0"

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
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui:1.6.8")
    implementation("androidx.compose.ui:ui-graphics:1.6.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.8")
    implementation("androidx.compose.material3:material3:1.2.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
    // navigation-compose removed: app uses a tab-switch pattern; the
    // dependency was declared but no NavHost/NavController exists.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.2")

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

    // Room DB -> Phase 1 (library)
    // Schema JSONs are exported to app/schemas for versioned migration tests.
    implementation("androidx.room:room-runtime:2.7.0-alpha13")
    implementation("androidx.room:room-ktx:2.7.0-alpha13")
    ksp("androidx.room:room-compiler:2.7.0-alpha13")



    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.11.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.3.1")
    // Real org.json for JVM tests (android.jar ships only method stubs).
    testImplementation("org.json:json:20240303")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val releaseConfig = android.signingConfigs.getByName("release")
val releaseKeystoreFile = releaseConfig.storeFile
val releaseStorePassword = releaseConfig.storePassword
val releaseKeyAlias = releaseConfig.keyAlias
val releaseKeyPassword = releaseConfig.keyPassword

tasks.register("validateReleaseSigning") {
    description = "Enforces fail-closed release signing validation. Fails build if release keystore or credentials are missing."
    val storeFile = releaseKeystoreFile
    val storePass = releaseStorePassword
    val alias = releaseKeyAlias
    val keyPass = releaseKeyPassword
    doLast {
        if (storeFile == null || !storeFile.exists()) {
            throw GradleException(
                "Release build failed-closed: Release keystore file is missing at '${storeFile?.absolutePath}'. " +
                "Production release builds must never fall back to debug signing."
            )
        }
        if (storePass.isNullOrEmpty() || alias.isNullOrEmpty() || keyPass.isNullOrEmpty()) {
            throw GradleException(
                "Release build failed-closed: Release signing credentials (storePassword, keyAlias, keyPassword) are incomplete."
            )
        }
    }
}

tasks.matching { it.name.startsWith("packageRelease") || it.name.startsWith("assembleRelease") }.configureEach {
    dependsOn("validateReleaseSigning")
}

