plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pdfreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pdfreader"
        minSdk = 24
        targetSdk = 35
        // Na GitHubie numer wersji rośnie z każdym budowaniem (-PversionCode=...).
        val build = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "1.$build"
    }

    // Stały klucz podpisu z sekretów GitHuba — dzięki niemu nowe wersje instalują się na starsze.
    // Bez niego (np. lokalnie w Android Studio) używany jest klucz debug.
    val keystoreFile = System.getenv("SIGNING_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = keystoreFile
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "release"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
