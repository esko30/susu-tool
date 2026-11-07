import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Ключ подписи релиза. Локально — из ../../signing/keystore.properties (вне репозитория),
 * в GitHub Actions — из переменных окружения, которые заполняются секретами репозитория.
 */
val signingProps = Properties().apply {
    val f = rootProject.file("../signing/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? = signingProps.getProperty(prop) ?: System.getenv(env)

android {
    namespace = "ru.susu.calendarsync"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.susu.calendarsync"
        minSdk = 26
        targetSdk = 35
        // При каждом выпуске увеличивайте оба значения; тег релиза на GitHub — v<versionName>.
        versionCode = 3
        versionName = "2.1"
        // Репозиторий GitHub, где приложение ищет обновления (владелец/имя).
        buildConfigField("String", "UPDATE_REPO", "\"${project.findProperty("updateRepo") ?: ""}\"")
    }

    signingConfigs {
        create("release") {
            val store = signingValue("storeFile", "SIGNING_STORE_FILE")
            if (store != null) {
                storeFile = if (signingProps.isEmpty) file(store) else rootProject.file("../signing/$store")
                storePassword = signingValue("storePassword", "SIGNING_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SIGNING_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Без ключа (например, в чужом форке) собираем с debug-ключом, чтобы сборка не падала.
            signingConfig = if (signingConfigs.getByName("release").storeFile != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.google.zxing:core:3.5.3")
}
