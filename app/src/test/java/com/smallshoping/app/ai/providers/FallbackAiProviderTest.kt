package com.smallshoping.app.ai.providers

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 044 验收：离线优先——本地规则 → 云端兜底 → 云端失败降级不阻塞营业。 */
class FallbackAiProviderTest {

    private fun request(text: String) = GatewayRequest(
        storeId = "STORE-1",
        deviceId = "DEVICE-1",
        appVersion = "0.1.0",
        inputText = text,
        allowedTools = listOf("add_sale_item")
    )

    @Test
    fun `本地命中：不请求云端`() {
        var cloudCalls = 0
        val cloud = CloudAiProvider(AiGatewayClient {
            cloudCalls++
            AiResponse.FinalText("cloud")
        })
        val provider = FallbackAiProvider(LocalRuleParser(), cloud)
        val response = provider.complete(request("卖两斤土豆"))
        assertTrue(response is AiResponse.ToolCall)
        assertEquals(0, cloudCalls)
    }

    @Test
    fun `本地未识别：云端兜底`() {
        val cloud = CloudAiProvider(AiGatewayClient { AiResponse.FinalText("云端回答") })
        val provider = FallbackAiProvider(LocalRuleParser(), cloud)
        val response = provider.complete(request("今天天气怎么样"))
        assertEquals(AiResponse.FinalText("云端回答"), response)
    }

    @Test
    fun `云端失败：MODEL_ERROR 降级，营业不阻塞`() {
        val cloud = CloudAiProvider(AiGatewayClient { throw IllegalStateException("no network") })
        val provider = FallbackAiProvider(LocalRuleParser(), cloud)
        val response = provider.complete(request("今天天气怎么样"))
        assertTrue(response is AiResponse.ModelError)

        // 编排层：本地句式照常可用，云端失败只降级为文本
        val root = CompositionRoot(cloudProvider = cloud)
        val fail = root.orchestrator.handle(root.inputAdapter.fromText("今天天气怎么样"))
        assertTrue((fail as OrchestratorReply.Text).text.contains("暂时不可用"))
        // 复杂句失败后，确定性业务句照常工作
        val biz = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue(biz is OrchestratorReply.Text || biz is OrchestratorReply.NeedsConfirm)
    }

    @Test
    fun `无云端：纯本地运行，未识别返回澄清`() {
        val provider = FallbackAiProvider(LocalRuleParser(), cloud = null)
        val known = provider.complete(request("卖两斤土豆"))
        assertTrue(known is AiResponse.ToolCall)
        val unknown = provider.complete(request("今天天气怎么样"))
        assertTrue(unknown is AiResponse.Clarification)
    }
}
