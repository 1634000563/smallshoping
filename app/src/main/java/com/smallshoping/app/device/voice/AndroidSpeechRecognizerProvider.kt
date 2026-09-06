package com.smallshoping.app.device.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Android 系统语音识别实现（RECORD_AUDIO 权限已声明于 Manifest）。
 *
 * 设备无语音服务（ERROR_NO_MATCH/无网络/无引擎）时回调 [SpeechResult.Error]，
 * 调用方必须回退到文本输入（离线宪法 #6：降级路径）。
 */
class AndroidSpeechRecognizerProvider(context: Context) : SpeechInputProvider {

    private val recognizer: SpeechRecognizer? = runCatching {
        SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
    }.getOrNull()

    private var callback: ((SpeechResult) -> Unit)? = null

    override fun listen(onResult: (SpeechResult) -> Unit) {
        val sr = recognizer ?: run {
            onResult(SpeechResult.Error("NO_ENGINE", "设备不支持语音识别，请用键盘输入"))
            return
        }
        callback = onResult
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val text = matches?.firstOrNull()?.takeIf { it.isNotBlank() }
                if (text != null) {
                    callback?.invoke(SpeechResult.Transcript(text))
                } else {
                    callback?.invoke(SpeechResult.NoMatch("没听清，请再说一次"))
                }
            }

            override fun onError(error: Int) {
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "没听清，请再说一次"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有说话"
                    SpeechRecognizer.ERROR_NETWORK -> "语音服务网络不可用"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音服务超时"
                    else -> "语音识别失败（code=$error）"
                }
                if (error == SpeechRecognizer.ERROR_NO_MATCH) {
                    callback?.invoke(SpeechResult.NoMatch(message))
                } else {
                    callback?.invoke(SpeechResult.Error("ASR_ERROR_$error", message))
                }
            }

            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        sr.startListening(intent)
    }

    override fun cancel() {
        runCatching { recognizer?.cancel() }
        callback = null
    }
}
