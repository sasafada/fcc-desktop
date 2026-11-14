plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.fcc.claude"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.fcc.claude"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Assinado com a chave debug: instalável por sideload (sem Play Store).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // rootfs.tar.gz precisa ficar sem compressão para openFd() funcionar e para ser rápido.
    androidResources { noCompress += listOf("gz") }

    packaging {
        // Necessário: proot precisa existir como arquivo real em nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
    }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
