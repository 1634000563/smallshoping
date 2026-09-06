package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.BuildConfig
import com.smallshoping.app.ai.providers.GatewayRequest

/** 店铺会话身份（spec 22：store_id/device_id 从第一天预留）。 */
data class StoreSession(
    val storeId: String,
    val deviceId: String,
    val appVersion: String = BuildConfig.VERSION_NAME
)

/**
 * 输入适配层：把语音转写文本或键盘文本统一适配为 [GatewayRequest]，
 * 供 Local/Cloud Provider 使用（打字与语音走完全相同的下游链路）。
 *
 * 上游 ASR 失败/无网不阻塞键盘输入；两者殊途同归进入 AI 编排。
 */
class InputAdapter(
    private val session: StoreSession,
    private val allowedTools: List<String>
) {

    init {
        require(allowedTools.isNotEmpty()) { "allowedTools 不能为空" }
    }

    /** 文本输入（键盘/扫码文本）。空白输入由调用方拦截，不产生请求。 */
    fun fromText(text: String): GatewayRequest =
        GatewayRequest(
            storeId = session.storeId,
            deviceId = session.deviceId,
            appVersion = session.appVersion,
            inputText = text.trim(),
            allowedTools = allowedTools
        )

    /** 语音转写文本输入：与键盘输入同一入口。 */
    fun fromAsr(transcript: String): GatewayRequest = fromText(transcript)
}
