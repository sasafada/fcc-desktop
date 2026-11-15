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
        // Sobe a cada build do GitHub Actions: atualizar por cima sempre é permitido.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.${System.getenv("GITHUB_RUN_NUMBER") ?: "0"}"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Chave FIXA (fcc.p12 na raiz). Antes, cada build do CI gerava uma chave nova,
        // e o Android recusava a atualização com "app inválido".
        create("fcc") {
            storeFile = rootProject.file("fcc.p12")
            storeType = "pkcs12"
            storePassword = System.getenv("FCC_STORE_PASSWORD") ?: ""
            keyAlias = "fcc"
            keyPassword = System.getenv("FCC_KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fcc")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // rootfs.tar.gz fica sem recompressão dentro do APK.
    androidResources { noCompress += listOf("gz") }

    packaging {
        jniLibs { useLegacyPackaging = true } // proot precisa existir como arquivo em nativeLibraryDir
    }
}

dependencies {
    implementation("com.github.termux.termux-app:terminal-view:v0.118.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
}
