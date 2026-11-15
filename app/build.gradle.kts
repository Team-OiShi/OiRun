import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.team_oishi.oirun"
    compileSdk = 35
    // Car API (GEAR_SELECTION); it is part of the framework on Android Automotive.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "io.github.team_oishi.oirun"
        minSdk = 28
        targetSdk = 35
        // CI numbers each release build so it installs over the previous one.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = "0.1.0"
    }

    // The release key lives only in CI secrets (see .github/workflows/release.yml); without it a
    // release build comes out unsigned.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE")?.let(::file)
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.fused.location.data)
    implementation(libs.pleos.vehicle)
    implementation(libs.maplibre.android)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
