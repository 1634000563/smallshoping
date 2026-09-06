package com.smallshoping.app.data.repository

import com.smallshoping.app.ai.context.SESSION_TTL_MILLIS
import com.smallshoping.app.ai.context.SessionContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySessionContextStoreTest {

    private val store = InMemorySessionContextStore()
    private val now = System.currentTimeMillis()

    private fun context(
        id: String = "DEVICE-1",
        expiresAt: Long = now + 60_000L,
        updatedAt: Long = now
    ) = SessionContext(
        deviceSessionId = id,
        activeSaleOrderId = "SALE-1",
        lastProductId = "P-1",
        expiresAtMillis = expiresAt,
        updatedAtMillis = updatedAt
    )

    @Test
    fun `正常路径：保存后可按会话 id 读回`() {
        store.save(context())
        val loaded = store.load("DEVICE-1")
        assertEquals("SALE-1", loaded?.activeSaleOrderId)
        assertEquals("P-1", loaded?.lastProductId)
        assertEquals(emptyMap<String, String>(), loaded?.contextJson)
    }

    @Test
    fun `边界输入：不存在的会话返回 null，clear 幂等`() {
        assertNull(store.load("NO-SUCH"))
        store.clear("NO-SUCH")
    }

    @Test
    fun `过期：过期会话读取视为不存在，clearExpired 只清过期`() {
        store.save(context(id = "OLD", expiresAt = now - 1_000L, updatedAt = now - 2_000L))
        store.save(context(id = "NEW", expiresAt = now + 60_000L, updatedAt = now))
        assertNull(store.load("OLD"))
        store.clearExpired(nowMillis = now)
        assertNull(store.load("OLD"))
        assertEquals("SALE-1", store.load("NEW")?.activeSaleOrderId)
    }

    @Test
    fun `滑动续期：refreshed 顺延过期时间`() {
        val original = context(expiresAt = now - 1L, updatedAt = now - 10L)
        assertTrue(original.isExpired)
        val renewed = original.refreshed(nowMillis = now, ttlMillis = SESSION_TTL_MILLIS)
        assertEquals(now, renewed.updatedAtMillis)
        assertEquals(now + SESSION_TTL_MILLIS, renewed.expiresAtMillis)
        assertTrue(!renewed.isExpired)
    }

    @Test
    fun `上下文字段：可保存最近意图与 Tool 结果摘要`() {
        store.save(
            context().copy(
                lastIntent = "sale_item",
                contextJson = mapOf("lastToolResult" to "已加入：土豆 2斤 7.6元")
            )
        )
        val loaded = store.load("DEVICE-1")
        assertEquals("sale_item", loaded?.lastIntent)
        assertEquals("已加入：土豆 2斤 7.6元", loaded?.contextJson?.get("lastToolResult"))
    }
}
