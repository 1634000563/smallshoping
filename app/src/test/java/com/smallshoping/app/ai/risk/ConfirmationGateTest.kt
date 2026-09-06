package com.smallshoping.app.ai.risk

import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationGateTest {

    private fun intent(requestId: String? = null) = Intent(
        type = IntentType.CHANGE_PRICE,
        entities = mapOf("product" to "土豆", "price" to "400"),
        requestId = requestId
    )

    @Test
    fun `正常路径：提出后仅最近一个可确认`() {
        val gate = ConfirmationGate()
        val first = gate.propose(intent("REQ-1"))
        val second = gate.propose(intent("REQ-2"))
        // 旧请求不能确认
        assertNull(gate.confirm("REQ-1"))
        // 新请求确认成功并返回原意图
        val confirmed = gate.confirm("REQ-2")
        assertEquals("REQ-2", confirmed?.requestId)
        assertEquals(IntentType.CHANGE_PRICE, confirmed?.type)
        // 已确认后不可重复确认；旧请求仍在等待并成为最近一个
        assertNull(gate.confirm("REQ-2"))
        assertEquals("REQ-1", gate.latestWaiting()?.requestId)
    }

    @Test
    fun `幂等：同 requestId 重复提出返回同一请求`() {
        val gate = ConfirmationGate()
        val p1 = gate.propose(intent("REQ-1"))
        val p2 = gate.propose(intent("REQ-1"))
        assertEquals(p1, p2)
        assertEquals("REQ-1", gate.latestWaiting()?.requestId)
    }

    @Test
    fun `拒绝：仅最近一个可拒绝`() {
        val gate = ConfirmationGate()
        gate.propose(intent("REQ-1"))
        gate.propose(intent("REQ-2"))
        assertFalse(gate.reject("REQ-1"))
        assertTrue(gate.reject("REQ-2"))
        assertEquals("REQ-1", gate.latestWaiting()?.requestId)
    }

    @Test
    fun `过期：超过 TTL 的请求自动失效`() {
        var now = 1_000_000L
        val gate = ConfirmationGate(ttlMillis = 60_000L, clock = { now })
        gate.propose(intent("REQ-1"))
        now += 60_000L // 恰好到 TTL
        gate.expire()
        assertNull(gate.latestWaiting())
        assertNull(gate.confirm("REQ-1"))
    }
}
