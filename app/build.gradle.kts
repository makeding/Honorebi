import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
//    id("kotlin-kapt")
    id("com.google.devtools.ksp")
    alias(libs.plugins.baselineprofile)
}

val releaseKeystorePropertiesFile =
    file("${System.getProperty("user.home")}/honorebi-release-keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.exists()) {
        releaseKeystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigningConfig = releaseKeystorePropertiesFile.exists()
val media3Version = libs.versions.media3.get()

android {
    namespace = "com.beeregg2001.komorebi"
    compileSdk = 37

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            // ★ true にすると、分割版(25MB)と、Universal版(50MB)の両方を出力してくれます！
            isUniversalApk = true
        }
    }

    defaultConfig {
        applicationId = "com.beeregg2001.Honorebi"
        minSdk = 24
        targetSdk = 34
        versionCode = 16 // 数値を1つ上げる
        versionName = "1.1.0-beta6"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // --- C++ ビルド設定 (1/2): ABIの設定 ---
        externalNativeBuild {
            cmake {
                // 必要に応じて C++ コンパイラ引数を追加
                cppFlags("-std=c++11")
            }
        }
        ndk {
            // 低スペック端末(Android TV等)で一般的なアーキテクチャに限定してビルド時間を短縮
            // 実機が 64bit なら arm64-v8a、32bit なら armeabi-v7a です
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    // --- C++ ビルド設定 (2/2): CMakeLists.txt のパス指定 ---
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    // NDKのバージョンを明示的に指定（Android Studio の SDK Manager でインストール済みのもの）
    // 指定しない場合は最新が使われますが、固定したほうがビルドが安定します
    // ndkVersion = "25.1.8937393"

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = file(releaseKeystoreProperties.getProperty("storeFile"))
                storePassword = releaseKeystoreProperties.getProperty("storePassword")
                keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
                keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
                storeType = "JKS"
            }
        }
    }

    buildTypes {
        release {
//            isMinifyEnabled = true       // コード圧縮を有効化
//            isShrinkResources = true     // 未使用の画像やリソースも削除
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // ★ BuildConfigクラスの生成を有効化
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.addAll(
                "-Xjvm-default=all",
                // ↓ この行を追加：TV Material3 の実験的API警告を無視（許可）します
                "-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api"
            )
        }
    }
}
configurations.configureEach {
    resolutionStrategy {
        // Jellyfin decoder 等の推移依存も、同じローカルパッチ版へ揃える。
        eachDependency {
            if (requested.group == "androidx.media3") {
                useVersion(media3Version)
                because("Komorebi broadcast playback patches")
            }
        }
    }
}
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

//kapt {
//    correctErrorTypes = true
//}

// app/build.gradle.kts
//configurations.all {
//    resolutionStrategy {
//        // Kotlin 2.x のメタデータを正しく読み取れるバージョンに強制
//        force("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.9.0")
//        force("org.jetbrains.kotlin:kotlin-stdlib:2.1.0")
//        force("org.jetbrains.kotlin:kotlin-reflect:2.1.0")
//    }
//}

dependencies {
    testImplementation("junit:junit:4.13.2")

    // 1. Compose BOM を最新に近いバージョンに更新 (ここが最重要)
    // 2023.10.01 だと Tv-Foundation 1.0.0-alpha11 と互換性がありません
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)

    // 2. 各ライブラリの指定 (バージョンは BOM が管理するので書かない)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation") // 追加
    implementation("androidx.compose.ui:ui-graphics")       // 追加

    // 【追加】拡張アイコンセット (CastConnected, Dns, Tv 等を使用するため)
    implementation("androidx.compose.material:material-icons-extended")

    // --- TV用ライブラリ ---
    // これらは BOM に含まれないため、バージョンを固定します
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tv.foundation)

    // --- Hilt ---
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.runtime)
    "baselineProfile"(project(":baselineprofile"))
    ksp(libs.hilt.compiler)

    // --- Retrofit ---
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging.interceptor)

    // --- OkHttp ---
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // --- Room (KSPへの移行を推奨) ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    ksp(libs.androidx.room.compiler)

    // --- Media3 ---
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation("androidx.media3:media3-common:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-session:$media3Version")
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.9.0+1")

    // --- その他 ---
    implementation(libs.coil.compose)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material)

    // Maven Central版の正しいID (DanmakuFlameMaster -> dfm)
    implementation("com.github.ctiao:dfm:0.9.25")

    // NDK Bitmap (バージョンは 0.9.21 を指定する必要があります)
    implementation("com.github.ctiao:ndkbitmap-armv7a:0.9.21")
//    implementation("com.github.ctiao:ndkbitmap-armv5:0.9.21")
//    implementation("com.github.ctiao:ndkbitmap-x86:0.9.21")

    compileOnly(libs.checker.qual)

    // Baseline Profiles のインストールを管理するライブラリ
    // Hilt Worker compiler must match the AndroidX Hilt runtime line.
    ksp(libs.androidx.hilt.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Gemini
    // --- Ktor Local Server & QR Code ---
    // BOMを使って、Geminiが裏で使うKtorクライアントとローカルサーバーのバージョンを強制統一
    implementation(platform(libs.ktor.bom))
    implementation("io.ktor:ktor-server-core") // ← バージョン番号はBOMが管理するので消す
    implementation("io.ktor:ktor-server-cio")  // ← バージョン番号はBOMが管理するので消す
    implementation(libs.zxing.core)

    // ★ 追加: SMB (ファイルライブラリ) 用
    implementation("eu.agno3.jcifs:jcifs-ng:2.1.10")

}
