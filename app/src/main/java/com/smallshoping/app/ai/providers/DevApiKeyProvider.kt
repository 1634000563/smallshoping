package com.smallshoping.app.ai.providers

import com.smallshoping.app.BuildConfig

/**
 * 开发用密钥提供者（安全宪法 #1：模型服务密钥不得内置在 APK）。
 *
 * - 构建默认值恒为空串：仓库与 APK 中不存在任何密钥；
 * - 开发者本地可用 Gradle 属性 `-PdevAiApiKey=xxx` 临时注入（BuildConfig 生成），
 *   该值只存在于本机构建产物，绝不提交；
 * - 正式发布一律走 AI Gateway（spec 21），客户端不持共享密钥。
 */
class DevApiKeyProvider {

    /** 无密钥时返回 null；调用方必须走降级路径（本地 parser / 明确报错）。 */
    fun keyOrNull(): String? = BuildConfig.DEV_AI_API_KEY.takeIf { it.isNotBlank() }
}
