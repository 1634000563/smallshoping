import java.util.Properties
import java.util.zip.ZipFile

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
    // Task 053：测试侧解析 tool-catalog.json（纯 JVM JSON 库）
    testImplementation("org.json:json:20240303")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")

    // Task 052 真机扫码：CameraX + MLKit 条码（standalone，离线可用，不依赖 Google Play 服务）
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.activity:activity:1.8.2")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}

// Task 054 发布门禁：一条命令跑全部关键自动化验证
// （全量单测含 Golden Eval / Architecture Guard / 各 Gate 验收 + 构建 APK）
tasks.register("releaseGate") {
    group = "verification"
    description = "V1 发布门禁：全量单测（含黄金语句集回归与架构守卫生）+ 构建 APK"
    dependsOn("testDebugUnitTest", "assembleDebug")
}

// Task 059：RC APK 验收——签名有效、无云密钥入包、清单包名与 minSdk 正确
val verifyRcApk by tasks.registering {
    group = "verification"
    description = "RC APK 验收：签名验证 + 云密钥扫描 + 清单校验"
    dependsOn("assembleDebug")
    doLast {
        val apk = file("build/outputs/apk/debug/app-debug.apk")
        check(apk.exists()) { "APK 不存在：${apk.absolutePath}" }
        check(apk.length() > 100_000) { "APK 大小异常：${apk.length()}" }

        // SDK 路径：优先 env，回退 local.properties（sdk.dir）
        val props = Properties()
        file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
        val sdk = System.getenv("ANDROID_HOME")
            ?: System.getenv("ANDROID_SDK_ROOT")
            ?: props.getProperty("sdk.dir")?.replace("\\", "/")?.let {
                if (it.startsWith("D:/")) it else "D:/$it"
            }
        check(!sdk.isNullOrBlank()) { "无法定位 Android SDK（ANDROID_HOME / local.properties）" }

        // 1) 签名验证（apksigner：验证失败退出码非 0）
        val apksignerJar = file("$sdk/build-tools/35.0.0/lib/apksigner.jar")
        check(apksignerJar.exists()) { "apksigner 不存在：${apksignerJar.absolutePath}" }
        val verifyExit = providers.exec {
            commandLine("java", "-jar", apksignerJar.absolutePath, "verify", apk.absolutePath)
        }.result.get().exitValue
        check(verifyExit == 0) { "APK 签名验证失败（apksigner exit=$verifyExit）" }
        val certOutput = providers.exec {
            commandLine(
                "java", "-jar", apksignerJar.absolutePath,
                "verify", "--print-certs", apk.absolutePath
            )
        }.standardOutput.asText.get()
        println("签名证书：${certOutput.lines().firstOrNull()?.substringAfter("DN: ") ?: "未知"}")

        // 2) 云密钥安全扫描（安全宪法 #1：云端 AI 密钥不得进 APK）
        ZipFile(apk).use { zip ->
            val patterns = listOf(Regex("sk-[A-Za-z0-9]{20,}"), Regex("AIza[0-9A-Za-z_-]{30,}"))
            val hits = ArrayList<String>()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || entry.size > 2_000_000) continue
                zip.getInputStream(entry).bufferedReader().useLines { lines ->
                    lines.forEachIndexed { index, line ->
                        if (patterns.any { it.containsMatchIn(line) }) {
                            hits.add("${entry.name} 第 ${index + 1} 行")
                        }
                    }
                }
            }
            check(hits.isEmpty()) { "APK 内发现疑似云密钥：$hits" }
        }

        // 3) 清单校验：包名与 minSdk（ADR-014）
        val aapt2 = file("$sdk/build-tools/35.0.0/aapt2.exe")
        check(aapt2.exists()) { "aapt2 不存在：${aapt2.absolutePath}" }
        val badging = providers.exec {
            commandLine(aapt2.absolutePath, "dump", "badging", apk.absolutePath)
        }.standardOutput.asText.get()
        check(badging.contains("package: name='com.smallshoping.app'")) {
            "APK 包名不符合 ADR-014：\n${badging.lines().firstOrNull()}"
        }
        check(badging.contains("minSdkVersion:'24'")) {
            "APK minSdk 不是 24（ADR-014）：\n${badging.lines().filter { it.contains("SdkVersion") }}"
        }
        val version = Regex("versionName='([^']+)'").find(badging)?.groupValues?.get(1)
        println("RC APK 验收通过：${apk.name}（${apk.length() / 1024} KB，versionName=$version）")
    }
}

// Task 059：V1 发布候选——发布门禁 + RC APK 验收一条命令
tasks.register("releaseCandidate") {
    group = "verification"
    description = "V1 发布候选：releaseGate（全量测试+黄金集+构建）+ RC APK 验收"
    dependsOn("releaseGate", "verifyRcApk")
}
