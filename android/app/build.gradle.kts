plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Backend base URL for this build.
 *
 * This is configuration, not a secret: the NEX backend holds the only AI key, and the app
 * carries no credentials of any kind. Override per build without editing source:
 *
 *   ./gradlew assembleRelease -PnexBackendUrl=https://your-own-service.onrender.com
 *
 * Only https addresses are accepted at runtime (see BackendUrlPolicy and the network
 * security config).
 */
val nexBackendUrl: String = (project.findProperty("nexBackendUrl") as String?)
    ?.takeIf { it.isNotBlank() }
    ?: "https://nex-companion-api.onrender.com"

android {
    namespace = "com.nexora.robot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexora.robot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("String", "BACKEND_BASE_URL", "\"$nexBackendUrl\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // No signingConfig on purpose: the repository never contains a keystore.
            // Sign locally:  apksigner sign --ks <your-keystore> app-release-unsigned.apk
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    testOptions {
        // One framework only: JUnit 4. Never mixed with JUnit 5 / Jupiter.
        unitTests.all { useJUnit() }
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = false
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        textReport = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
            )
        }
    }
}

dependencies {
    // --- AndroidX / UI ---------------------------------------------------
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")

    // --- Coroutines ------------------------------------------------------
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- HTTPS only, no credential handling ------------------------------
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // --- Optional camera (a single frame, in memory only) ----------------
    implementation("androidx.camera:camera-core:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // --- Tests: pure JVM, JUnit 4 ---------------------------------------
    testImplementation("junit:junit:4.13.2")
}
