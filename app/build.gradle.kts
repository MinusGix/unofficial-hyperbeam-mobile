plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment (CI decodes a repository secret);
// without it, release builds fall back to the debug key.
val releaseKeystore: String? = System.getenv("HB_KEYSTORE")

android {
    namespace = "dev.minusgix.hyperbeam"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.minusgix.hyperbeam"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("HB_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("HB_VERSION_NAME") ?: "0.1.0-dev"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("HB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HB_KEY_ALIAS")
                keyPassword = System.getenv("HB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("dev.minusgix.hyperbeam:core")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.webkit:webkit:1.13.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
