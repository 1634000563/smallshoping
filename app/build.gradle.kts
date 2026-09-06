plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.smallshoping.app"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.smallshoping.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        // 开发密钥：默认空（APK 不含密钥，安全宪法 #1）；
        // 本机临时注入用 -PdevAiApiKey=xxx，绝不提交该属性
        val devAiApiKey = project.findProperty("devAiApiKey") as String? ?: ""
        buildConfigField("String", "DEV_AI_API_KEY", "\"$devAiApiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24/25 使用 java.time 需要脱糖（spec 03 §6 业务日期）
        isCoreLibraryDesugaringEnabled = true
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Architecture Guard 的代码级自动检查
    testImplementation("com.tngtech.archunit:archunit:1.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")
}
