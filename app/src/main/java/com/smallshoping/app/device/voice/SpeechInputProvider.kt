package com.smallshoping.app.device.voice

/** 语音识别结果（设备不可用时必须有降级路径，device 局部规则）。 */
sealed interface SpeechResult {
    data class Transcript(val text: String) : SpeechResult
    data class NoMatch(val message: String) : SpeechResult
    data class Error(val code: String, val message: String) : SpeechResult
}

/**
 * 语音输入适配接口：Android SpeechRecognizer、第三方 ASR 均经此隔离。
 * 实现必须在主线程回调（Android 限制），真实设备行为验证见 Task 052。
 */
interface SpeechInputProvider {

    /** 开始监听；结果/失败一次回调。 */
    fun listen(onResult: (SpeechResult) -> Unit)

    fun cancel()
}
