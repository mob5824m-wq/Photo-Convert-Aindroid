plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val resolvedVersionCode = (findProperty("appVersionCode") as? String)?.toIntOrNull() ?: 1
val resolvedVersionName = (findProperty("appVersionName") as? String)?.takeIf { it.isNotBlank() } ?: "1.0.0"

android {
    namespace = "com.pixelnas.photosaver"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pixelnas.photosaver"
        minSdk = 26 // Android 8.0 Oreo -> supports Google Pixel 1, 2, 3, 3a, 4, 4a, 4a 5G, Pixel 5 & daily Android phones
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
            val defaultRepoKs = file("../keystore/pixelnas-release.p12")
            val chosenKs = when {
                !envKs.isNullOrBlank() && file(envKs).exists() -> file(envKs)
                defaultRepoKs.exists() -> defaultRepoKs
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
                "META-INF/io.netty.versions.properties"
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

    // Jetpack Compose Material 3 UI
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // Full EXIF Date, Camera & GPS preservation across image resize
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Hardware-accelerated MediaCodec 1080p MP4 Storage Saver video transcoding
    implementation("androidx.media3:media3-transformer:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Embedded LAN HTTP Web Portal + REST API + RFC 4918 WebDAV Server
    implementation("io.ktor:ktor-server-core-jvm:2.3.12")
    implementation("io.ktor:ktor-server-cio-jvm:2.3.12")

    // Embedded Unprivileged LAN FTP Server (:2121)
    implementation("org.apache.ftpserver:ftpserver-core:1.2.0")
    implementation("org.slf4j:slf4j-nop:1.7.36")

    // Native LAN QR Code generation on phone screen
    implementation("com.google.zxing:core:3.5.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.hierynomus:smbj:0.13.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
