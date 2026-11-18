plugins {
    id("com.android.application")
}

android {
    namespace = "com.deepdim"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.deepdim"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/deepdim.jks")
            storePassword = "deepdim"
            keyAlias = "deepdim"
            keyPassword = "deepdim"
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
