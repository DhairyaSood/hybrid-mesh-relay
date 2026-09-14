plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.hybridmesh.relay"

    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.hybridmesh.relay"
        minSdk = 26
        targetSdk = 37

        // These can be overridden by GitHub Actions using:
        // -PversionCode=...
        // -PversionName=...
        versionCode = providers.gradleProperty("versionCode")
            .orElse("1")
            .get()
            .toInt()

        versionName = providers.gradleProperty("versionName")
            .orElse("1.0")
            .get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
    create("release") {
        storeFile = file("${rootProject.projectDir}/release-key.jks")
        storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
        keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
        keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
    }
}

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")

            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }
}

dependencies {

    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Ui
    implementation("androidx.compose.material:material-icons-extended")

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    // Unit tests
    testImplementation(libs.junit)

    // Instrumentation tests
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    // Debug
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}