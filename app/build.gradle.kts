plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

/**
 * Экранирует секрет для вставки в строковый литерал BuildConfig (генерируется как Java):
 * убирает переводы строк, экранирует `\` и `"`, а `$` заменяет на unicode-escape `\u0024`
 * (Java не допускает escape `\$` и упадёт с "illegal escape character",
 * а `\u0024` одинаково безопасен и для Java, и для Kotlin-шаблонов).
 */
fun escapeForBuildConfig(raw: String?): String = (raw ?: "")
    .replace("\r", "")
    .replace("\n", "")
    .trim()
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\$", "\\u0024")

ksp {
    // Экспорт схемы Room для будущих Migration (без fallbackToDestructiveMigration)
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "ru.pokanevich.familybudget"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.pokanevich.familybudget"
        minSdk = 26
        targetSdk = 34
        versionCode = 17
        versionName = "3.4.1"

        val syncToken = escapeForBuildConfig(System.getenv("SYNC_GITHUB_TOKEN"))
        val geminiApiKey = escapeForBuildConfig(System.getenv("GEMINI_API_KEY"))
        buildConfigField("String", "DEFAULT_SYNC_TOKEN", "\"$syncToken\"")
        buildConfigField("String", "DEFAULT_GEMINI_API_KEY", "\"$geminiApiKey\"")

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            // Release-подпись активируется только при полном и непустом наборе из 4 переменных,
            // иначе сборка безопасно откатывается на debug-подпись (без падения на полупустых секретах).
            val keystorePath = System.getenv("KEYSTORE_PATH")?.trim().orEmpty()
            val keystorePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()
            val releaseKeyAlias = System.getenv("KEY_ALIAS")?.trim().orEmpty()
            val releaseKeyPassword = System.getenv("KEY_PASSWORD").orEmpty()
            val keystoreFile = keystorePath.takeIf { it.isNotBlank() }?.let { file(it) }
            val isReleaseSigningComplete = keystoreFile != null &&
                keystoreFile.isFile &&
                keystoreFile.length() > 0L &&
                keystorePassword.isNotBlank() &&
                releaseKeyAlias.isNotBlank() &&
                releaseKeyPassword.isNotBlank()
            if (isReleaseSigningComplete) {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            } else if (keystorePath.isNotBlank() || keystorePassword.isNotBlank() ||
                releaseKeyAlias.isNotBlank() || releaseKeyPassword.isNotBlank()
            ) {
                logger.warn("Release signing config is incomplete (need KEYSTORE_PATH file, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) - falling back to debug signing")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.findByName("release")
            signingConfig = if (releaseSigning?.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ""
            isDebuggable = true
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
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.11"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // UI & Material 3 Adaptive for Samsung Galaxy Z Fold 4/5
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material3:material3-window-size-class")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.window:window:1.2.0")

    // Room Local Database (Offline-First)
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // CameraX & ML Kit Barcode Scanning (ФНС QR receipts)
    val cameraxVersion = "1.3.3"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.2.0")

    // Coroutines & Network for Cloud Sync & Gemini Free Tier AI Advisor
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.0")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
