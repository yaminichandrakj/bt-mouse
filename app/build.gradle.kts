plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.btmouse.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.btmouse.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 24
        versionName = "1.3.17"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() } ?: "release.keystore"
            val keystoreFile = file(keystorePath)
            val ksPassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            val kAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
            val kPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }

            if (keystoreFile.exists() && ksPassword != null && kAlias != null && kPassword != null) {
                storeFile = keystoreFile
                storePassword = ksPassword
                keyAlias = kAlias
                keyPassword = kPassword
            } else if (keystoreFile.exists()) {
                storeFile = keystoreFile
                storePassword = ksPassword ?: "btmouse123"
                keyAlias = kAlias ?: "btmouse"
                keyPassword = kPassword ?: "btmouse123"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation("androidx.fragment:fragment-ktx:1.7.0")
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    implementation(libs.play.services.code.scanner)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.ui.tooling)
}
