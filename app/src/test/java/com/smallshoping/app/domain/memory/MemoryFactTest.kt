package com.smallshoping.app.domain.memory

import org.junit.Assert.assertThrows
import org.junit.Test

/** Task 023：记忆事实模型校验。 */
class MemoryFactTest {

    private fun fact(
        scopeType: MemoryScopeType = MemoryScopeType.STORE,
        scopeId: String = "STORE-1",
        factType: String = "default_unit",
        key: String = "weight",
        valueJson: String = """{"unit":"jin"}""",
        confidence: Int = 90,
        source: MemorySource = MemorySource.USER_CONFIRMED
    ) = MemoryFact(
        scopeType = scopeType, scopeId = scopeId, factType = factType, key = key,
        valueJson = valueJson, confidence = confidence, source = source
    )

    @Test
    fun `合法事实：USER_CONFIRMED 与 OBSERVED_PATTERN 均可构造`() {
        fact()
        fact(source = MemorySource.OBSERVED_PATTERN) // 观察型记忆默认从未被确认（lastConfirmedAtMillis=null）
    }

    @Test
    fun `边界输入：空字段与越界置信度拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { fact(scopeId = " ") }
        assertThrows(IllegalArgumentException::class.java) { fact(factType = "") }
        assertThrows(IllegalArgumentException::class.java) { fact(key = "") }
        assertThrows(IllegalArgumentException::class.java) { fact(valueJson = "") }
        assertThrows(IllegalArgumentException::class.java) { fact(confidence = -1) }
        assertThrows(IllegalArgumentException::class.java) { fact(confidence = 101) }
    }
}
