plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val resolvedVersionCode = (findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 1
val resolvedVersionName = (findProperty("appVersionName") as? String)?.takeIf { it.isNotBlank() } ?: "1.0.0"

android {
    namespace = "com.pixelnas.household"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pixelnas.household"
        minSdk = 26 // Android 8.0+ across Samsung, Pixel, OnePlus, Motorola, Xiaomi & all household phones
        targetSdk = 34
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("releaseSigned") {
            val envKs = System.getenv("KEYSTORE_PATH")
            val sharedRepoKs = file("../../android-app/keystore/pixelnas-release.p12")
            val chosenKs = when {
                !envKs.isNullOrBlank() && file(envKs).exists() -> file(envKs)
                sharedRepoKs.exists() -> sharedRepoKs
                else -> null
            }
            if (chosenKs != null) {
                storeFile = chosenKs
                storeType = if (chosenKs.extension.equals("p12", ignoreCase = true)) "PKCS12" else "JKS"
                storePassword = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() } ?: "pixelnas2026"
                keyAlias = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() } ?: "pixelnas"
                keyPassword = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() } ?: "pixelnas2026"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val relCfg = signingConfigs.getByName("releaseSigned")
            signingConfig = if (relCfg.storeFile != null) {
                relCfg
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/*.kotlin_module",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "OSGI-INF/MANIFEST.MF"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Jetpack Compose Material 3 UI (Emerald / Teal Household Samba Theme)
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Native Samba / SMB2 / SMB3 File Share Protocol Client (ports 445 & 4445, Guest & NTLMv2)
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // Read EXIF capture timestamps to preserve file timestamps directly over Samba FileBasicInformation
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Automatic background Wi-Fi Camera Roll backup to NAS Samba share
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
