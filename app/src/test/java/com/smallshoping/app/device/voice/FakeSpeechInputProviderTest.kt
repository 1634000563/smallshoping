package com.smallshoping.app.device.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 供测试用的假语音输入（device 局部规则：真实设备行为必须有假实现供单测）。 */
class FakeSpeechInputProvider(
    private val result: SpeechResult
) : SpeechInputProvider {

    var canceled = false
        private set

    override fun listen(onResult: (SpeechResult) -> Unit) {
        onResult(result)
    }

    override fun cancel() {
        canceled = true
    }
}

class FakeSpeechInputProviderTest {

    @Test
    fun `正常路径：转写结果一次回调`() {
        val provider = FakeSpeechInputProvider(SpeechResult.Transcript("卖两斤土豆"))
        var received: SpeechResult? = null
        provider.listen { received = it }
        assertEquals(SpeechResult.Transcript("卖两斤土豆"), received)
    }

    @Test
    fun `异常路径：NoMatch 与 Error 均明确回调，不伪造成功`() {
        val noMatch = FakeSpeechInputProvider(SpeechResult.NoMatch("没听清"))
        var received: SpeechResult? = null
        noMatch.listen { received = it }
        assertTrue(received is SpeechResult.NoMatch)

        val error = FakeSpeechInputProvider(SpeechResult.Error("ASR_ERROR_2", "网络不可用"))
        error.listen { received = it }
        assertTrue(received is SpeechResult.Error)
        assertEquals("ASR_ERROR_2", (received as SpeechResult.Error).code)
    }

    @Test
    fun `取消：cancel 可达`() {
        val provider = FakeSpeechInputProvider(SpeechResult.Transcript("x"))
        provider.cancel()
        assertTrue(provider.canceled)
    }
}
