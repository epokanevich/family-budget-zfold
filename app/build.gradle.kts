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

data class FirebaseBuildConfigData(
    val projectId: String,
    val appId: String,
    val apiKey: String,
    val storageBucket: String
)

@Suppress("UNCHECKED_CAST")
fun resolveFirebaseConfig(
    candidateFiles: List<File>,
    targetPackage: String
): FirebaseBuildConfigData {
    val file = candidateFiles.firstOrNull { it.isFile && it.length() > 0L }
    if (file != null) {
        runCatching {
            val root = groovy.json.JsonSlurper().parse(file) as? Map<String, Any?>
            val projectInfo = root?.get("project_info") as? Map<String, Any?>
            val projectId = (projectInfo?.get("project_id") as? String).orEmpty()
            val storageBucket = (projectInfo?.get("storage_bucket") as? String).orEmpty()
            val clients = root?.get("client") as? List<Map<String, Any?>> ?: emptyList()
            val matchedClient = clients.firstOrNull { client ->
                val clientInfo = client["client_info"] as? Map<String, Any?>
                val androidInfo = clientInfo?.get("android_client_info") as? Map<String, Any?>
                androidInfo?.get("package_name") == targetPackage
            } ?: clients.firstOrNull()
            val clientInfo = matchedClient?.get("client_info") as? Map<String, Any?>
            val appId = (clientInfo?.get("mobilesdk_app_id") as? String).orEmpty()
            val apiKeys = matchedClient?.get("api_key") as? List<Map<String, Any?>> ?: emptyList()
            val apiKey = (apiKeys.firstOrNull()?.get("current_key") as? String).orEmpty()
            if (projectId.isNotBlank() && appId.isNotBlank() && apiKey.isNotBlank()) {
                return FirebaseBuildConfigData(
                    projectId = escapeForBuildConfig(projectId),
                    appId = escapeForBuildConfig(appId),
                    apiKey = escapeForBuildConfig(apiKey),
                    storageBucket = escapeForBuildConfig(storageBucket)
                )
            }
        }
    }
    return FirebaseBuildConfigData(
        projectId = escapeForBuildConfig(System.getenv("FIREBASE_PROJECT_ID")),
        appId = escapeForBuildConfig(System.getenv("FIREBASE_APP_ID")),
        apiKey = escapeForBuildConfig(System.getenv("FIREBASE_API_KEY")),
        storageBucket = escapeForBuildConfig(System.getenv("FIREBASE_STORAGE_BUCKET"))
    )
}

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
        versionCode = 28
        versionName = "3.7.4"

        val syncToken = escapeForBuildConfig(System.getenv("SYNC_GITHUB_TOKEN"))
        val geminiApiKey = escapeForBuildConfig(System.getenv("GEMINI_API_KEY"))
        val defaultFamilyCode = escapeForBuildConfig(System.getenv("DEFAULT_FAMILY_SPACE_CODE"))
            .ifBlank { "POKANE-FAMILY" }
        val defaultUserName = escapeForBuildConfig(System.getenv("DEFAULT_USER_NAME"))
            .ifBlank { "Евгений" }
        val isSoloProfile = defaultUserName !in setOf("Евгений", "Анжелика")
        buildConfigField("String", "DEFAULT_SYNC_TOKEN", "\"$syncToken\"")
        buildConfigField("String", "DEFAULT_GEMINI_API_KEY", "\"$geminiApiKey\"")
        buildConfigField("String", "DEFAULT_FAMILY_SPACE_CODE", "\"$defaultFamilyCode\"")
        buildConfigField("String", "DEFAULT_USER_NAME", "\"$defaultUserName\"")
        buildConfigField("Boolean", "IS_SOLO_BUDGET_PROFILE", isSoloProfile.toString())
        buildConfigField("Boolean", "IS_SPACE_CODE_LOCKED", "false")
        buildConfigField("Double", "DEFAULT_MONTHLY_BUDGET_RUB", "200000.0")
        buildConfigField("String", "SYNC_CONTOUR", "\"FAMILY\"")

        val defaultFirebase = resolveFirebaseConfig(
            candidateFiles = listOf(
                file("src/family/google-services.json"),
                file("google-services.json")
            ),
            targetPackage = "ru.pokanevich.familybudget"
        )
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${defaultFirebase.projectId}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${defaultFirebase.appId}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${defaultFirebase.apiKey}\"")
        buildConfigField("String", "FIREBASE_STORAGE_BUCKET", "\"${defaultFirebase.storageBucket}\"")

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
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.fragment:fragment-ktx:1.7.1")
    implementation("androidx.biometric:biometric:1.1.0")

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

    // Firebase Cloud Firestore & Anonymous Auth (BoM 33.1.2 compatible with Kotlin 1.9.23)
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.guava:guava:32.1.3-android")

    // Unit Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
