plugins {
    id("com.android.application")
}

android {
    namespace = "com.devicespooflab.hooks"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.spoofmydevice"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        viewBinding = true
    }

    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.fragment:fragment:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    compileOnly("de.robv.android.xposed:api:82")
}
