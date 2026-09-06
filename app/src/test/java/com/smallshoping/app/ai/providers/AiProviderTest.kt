package com.smallshoping.app.ai.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiProviderTest {

    private fun request(text: String) = GatewayRequest(
        storeId = "STORE-1",
        deviceId = "DEVICE-1",
        appVersion = "0.1.0",
        inputText = text,
        allowedTools = listOf("find_product", "add_sale_item", "get_today_sales", "recharge_member", "purchase_in")
    )

    private val parser = LocalRuleParser()

    @Test
    fun `本地解析：今天卖了多少钱 → get_today_sales`() {
        val r = parser.complete(request("今天卖了多少钱"))
        assertTrue(r is AiResponse.ToolCall)
        assertEquals("get_today_sales", (r as AiResponse.ToolCall).toolName)
    }

    @Test
    fun `本地解析：卖两斤土豆 → add_sale_item（中文数字转阿拉伯）`() {
        val r = parser.complete(request("卖两斤土豆")) as AiResponse.ToolCall
        assertEquals("add_sale_item", r.toolName)
        assertEquals("土豆", r.entities["product"])
        assertEquals("2斤", r.entities["quantity"])
    }

    @Test
    fun `本地解析：来2斤土豆 → add_sale_item（数字+单位）`() {
        val r = parser.complete(request("来2斤土豆")) as AiResponse.ToolCall
        assertEquals("add_sale_item", r.toolName)
        assertEquals("土豆", r.entities["product"])
        assertEquals("2斤", r.entities["quantity"])
    }

    @Test
    fun `本地解析：土豆多少钱 → find_product`() {
        val r = parser.complete(request("土豆多少钱")) as AiResponse.ToolCall
        assertEquals("find_product", r.toolName)
        assertEquals("土豆", r.entities["query"])
    }

    @Test
    fun `本地解析：给张姐充200 → recharge_member（元转分无浮点）`() {
        val r = parser.complete(request("给张姐充200")) as AiResponse.ToolCall
        assertEquals("recharge_member", r.toolName)
        assertEquals("张姐", r.entities["member"])
        assertEquals("20000", r.entities["amount"])
        val r2 = parser.complete(request("给张姐充2.5元")) as AiResponse.ToolCall
        assertEquals("250", r2.entities["amount"])
    }

    @Test
    fun `本地解析：省略「给」与块毛金额（Task 022）`() {
        val r = parser.complete(request("张姐充200")) as AiResponse.ToolCall
        assertEquals("recharge_member", r.toolName)
        assertEquals("张姐", r.entities["member"])
        assertEquals("20000", r.entities["amount"])
        val r2 = parser.complete(request("给张姐充2块8")) as AiResponse.ToolCall
        assertEquals("280", r2.entities["amount"])
        val r3 = parser.complete(request("给张姐充2块8毛")) as AiResponse.ToolCall
        assertEquals("280", r3.entities["amount"])
    }

    @Test
    fun `本地解析：进100斤土豆 → purchase_in`() {
        val r = parser.complete(request("进100斤土豆")) as AiResponse.ToolCall
        assertEquals("purchase_in", r.toolName)
        assertEquals("土豆", r.entities["product"])
        assertEquals("100斤", r.entities["quantity"])
    }

    @Test
    fun `本地解析：还是昨天那个价格 → apply_yesterday_price（Task 026）`() {
        val r = parser.complete(request("还是昨天那个价格")) as AiResponse.ToolCall
        assertEquals("apply_yesterday_price", r.toolName)
        assertTrue(r.entities.isEmpty())
        val r2 = parser.complete(request("用昨天的价格")) as AiResponse.ToolCall
        assertEquals("apply_yesterday_price", r2.toolName)
        // 含「昨天」但无「价」不误触发（如「今天卖了多少钱」已由上一分支处理）
        assertTrue(parser.complete(request("昨天卖了多少钱")) is AiResponse.ToolCall)
        assertEquals(
            "get_today_sales",
            (parser.complete(request("昨天卖了多少钱")) as AiResponse.ToolCall).toolName
        )
    }

    @Test
    fun `本地解析：老张上次那些螺丝再来两盒 → reorder_last_item（Task 027）`() {
        val r = parser.complete(request("老张上次那些螺丝再来两盒")) as AiResponse.ToolCall
        assertEquals("reorder_last_item", r.toolName)
        assertEquals("老张", r.entities["customer"])
        assertEquals("螺丝", r.entities["product"])
        assertEquals("2盒", r.entities["quantity"])
        // 商品名省略（客户记忆兜底）
        val r2 = parser.complete(request("老张上次那些再来两盒")) as AiResponse.ToolCall
        assertEquals("reorder_last_item", r2.toolName)
        assertEquals("老张", r2.entities["customer"])
        assertTrue(r2.entities["product"] == null)
        assertEquals("2盒", r2.entities["quantity"])
        // 「上次的」变体
        val r3 = parser.complete(request("老张上次的螺丝再来一盒")) as AiResponse.ToolCall
        assertEquals("螺丝", r3.entities["product"])
        assertEquals("1盒", r3.entities["quantity"])
    }

    @Test
    fun `本地解析：损耗两斤土豆 → record_loss（Task 033）`() {
        val r = parser.complete(request("损耗两斤土豆")) as AiResponse.ToolCall
        assertEquals("record_loss", r.toolName)
        assertEquals("土豆", r.entities["product"])
        assertEquals("2斤", r.entities["quantity"])
        val r2 = parser.complete(request("土豆坏了2斤")) as AiResponse.ToolCall
        assertEquals("record_loss", r2.toolName)
        assertEquals("土豆", r2.entities["product"])
        assertEquals("2斤", r2.entities["quantity"])
        val r3 = parser.complete(request("损耗半斤土豆")) as AiResponse.ToolCall
        assertEquals("0.5斤", r3.entities["quantity"])
    }

    @Test
    fun `无法识别：返回澄清而非猜测`() {
        val r = parser.complete(request("今天天气怎么样"))
        assertTrue(r is AiResponse.Clarification)
        // 空文本在请求模型层即被拒绝
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            request("")
        }
    }

    @Test
    fun `金额解析边界：三位小数拒绝，0元合法`() {
        val bad = parser.complete(request("给张姐充2.555元"))
        assertTrue(bad is AiResponse.Clarification)
        val zero = parser.complete(request("给张姐充0元")) as AiResponse.ToolCall
        assertEquals("0", zero.entities["amount"])
    }

    @Test
    fun `云端 Provider：成功透传，异常映射为 MODEL_ERROR`() {
        val okClient = AiGatewayClient { AiResponse.FinalText("好的") }
        assertEquals(AiResponse.FinalText("好的"), CloudAiProvider(okClient).complete(request("你好")))

        val boomClient = AiGatewayClient { throw IllegalStateException("timeout") }
        val r = CloudAiProvider(boomClient).complete(request("你好"))
        assertTrue(r is AiResponse.ModelError)
        assertEquals("MODEL_ERROR", (r as AiResponse.ModelError).code)

        val limitedClient = AiGatewayClient { AiResponse.RateLimited(retryAfterSeconds = 3) }
        assertEquals(AiResponse.RateLimited(3), CloudAiProvider(limitedClient).complete(request("你好")))
    }

    @Test
    fun `密钥安全：默认构建不含任何密钥`() {
        assertNull(DevApiKeyProvider().keyOrNull())
    }

    @Test
    fun `请求模型：最小权限校验`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GatewayRequest("S", "D", "1.0", inputText = "  ", allowedTools = listOf("a"))
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            GatewayRequest("S", "D", "1.0", inputText = "土豆", allowedTools = emptyList())
        }
    }
}
