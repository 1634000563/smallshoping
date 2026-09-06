package com.smallshoping.app.ai.orchestrator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class InputAdapterTest {

    private val adapter = InputAdapter(
        session = StoreSession(storeId = "STORE-1", deviceId = "DEVICE-1", appVersion = "0.1.0"),
        allowedTools = listOf("find_product", "add_sale_item", "get_today_sales")
    )

    @Test
    fun `正常路径：文本与语音转写走同一请求模型`() {
        val fromText = adapter.fromText(" 卖两斤土豆 ")
        val fromAsr = adapter.fromAsr("卖两斤土豆")
        assertEquals(fromText, fromAsr)
        assertEquals("STORE-1", fromText.storeId)
        assertEquals("DEVICE-1", fromText.deviceId)
        assertEquals("0.1.0", fromText.appVersion)
        assertEquals("卖两斤土豆", fromText.inputText)
        assertEquals(3, fromText.allowedTools.size)
        assertEquals("v1", fromText.promptVersion)
        assertEquals("v1", fromText.toolSchemaVersion)
    }

    @Test
    fun `边界输入：空白文本在请求模型层被拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            adapter.fromText("   ")
        }
    }

    @Test
    fun `异常路径：allowedTools 为空拒绝构造`() {
        assertThrows(IllegalArgumentException::class.java) {
            InputAdapter(
                session = StoreSession("S", "D"),
                allowedTools = emptyList()
            )
        }
    }

    @Test
    fun `上下文引用：可携带当前订单与最近实体`() {
        val request = adapter.fromText("还是昨天那个价格").copy(
            currentSaleId = "SALE-1",
            recentEntities = listOf("product=P-1")
        )
        assertEquals("SALE-1", request.currentSaleId)
        assertEquals(listOf("product=P-1"), request.recentEntities)
    }
}
