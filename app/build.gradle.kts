import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sergei.settape"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sergei.settape"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.3"
    }

    // One fixed key, kept in the repo, so every build installs over the previous one
    // without uninstalling first. Fine for a personal app; see the README.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/settape.jks")
            storeType = "pkcs12"
            storePassword = "settape"
            keyAlias = "settape"
            keyPassword = "settape"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
