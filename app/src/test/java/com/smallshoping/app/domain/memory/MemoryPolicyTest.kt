package com.smallshoping.app.domain.memory

import com.smallshoping.app.data.repository.InMemoryMemoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 024 验收：写入许可与读取优先级（spec 06 §2/§3）。 */
class MemoryPolicyTest {

    private val store = InMemoryMemoryStore()
    private var clock = 1_000_000L

    private fun policy(
        threshold: Int = 3,
        windowMillis: Long = 1000L
    ) = MemoryWritePolicy(store, threshold, windowMillis) { clock }

    private fun fact(
        scopeType: MemoryScopeType = MemoryScopeType.STORE,
        scopeId: String = "STORE-1",
        factType: String = "default_unit",
        key: String = "weight",
        valueJson: String = """{"unit":"jin"}""",
        confidence: Int = 60
    ) = MemoryFact(
        scopeType = scopeType, scopeId = scopeId, factType = factType, key = key,
        valueJson = valueJson, confidence = confidence, source = MemorySource.OBSERVED_PATTERN
    )

    @Test
    fun `确认写入：USER_CONFIRMED 置信度 100`() {
        val p = policy()
        val result = p.writeConfirmed(fact())
        assertTrue(result is MemoryWritePolicy.MemoryWriteResult.Written)
        val stored = (result as MemoryWritePolicy.MemoryWriteResult.Written).fact
        assertEquals(MemorySource.USER_CONFIRMED, stored.source)
        assertEquals(100, stored.confidence)
    }

    @Test
    fun `观察写入：达阈值才写 OBSERVED_PATTERN（置信度 60）`() {
        val p = policy(threshold = 3)
        val f = fact()
        assertTrue(p.observe(f) is MemoryWritePolicy.MemoryWriteResult.NeedMoreObservations)
        assertTrue(p.observe(f) is MemoryWritePolicy.MemoryWriteResult.NeedMoreObservations)
        val third = p.observe(f)
        assertTrue(third is MemoryWritePolicy.MemoryWriteResult.Written)
        val stored = (third as MemoryWritePolicy.MemoryWriteResult.Written).fact
        assertEquals(MemorySource.OBSERVED_PATTERN, stored.source)
        assertEquals(60, stored.confidence)
        assertEquals(1, store.query(MemoryScopeType.STORE, "STORE-1").size)
    }

    @Test
    fun `观察窗口：超窗观察不计入`() {
        val p = policy(threshold = 2, windowMillis = 1000L)
        val f = fact()
        p.observe(f) // t=1000000
        clock += 2000L // 超过 1000ms 窗口
        val second = p.observe(f)
        assertTrue(second is MemoryWritePolicy.MemoryWriteResult.NeedMoreObservations)
        val needMore = second as MemoryWritePolicy.MemoryWriteResult.NeedMoreObservations
        assertEquals(1, needMore.observed)
    }

    @Test
    fun `推断不得覆盖已确认事实（spec 06 §2）`() {
        val p = policy()
        p.writeConfirmed(fact())
        val result = p.observe(fact(valueJson = """{"unit":"kg"}"""))
        assertTrue(result is MemoryWritePolicy.MemoryWriteResult.BlockedByConfirmedFact)
        // 已确认事实原值未变
        assertEquals(
            """{"unit":"jin"}""",
            store.findByKey(MemoryScopeType.STORE, "STORE-1", "default_unit", "weight")?.valueJson
        )
    }

    @Test
    fun `确认升级观察事实：同键不产生重复行`() {
        val p = policy(threshold = 1)
        p.observe(fact())
        p.writeConfirmed(fact(valueJson = """{"unit":"kg"}"""))
        val list = store.query(MemoryScopeType.STORE, "STORE-1")
        assertEquals(1, list.size)
        assertEquals(MemorySource.USER_CONFIRMED, list[0].source)
        assertEquals(100, list[0].confidence)
    }

    @Test
    fun `读取排序：确认记忆在前，同源按置信度降序`() {
        val p = policy(threshold = 1)
        p.observe(fact(factType = "a", key = "low", valueJson = "1"))
        p.writeConfirmed(fact(factType = "a", key = "high", valueJson = "2"))
        p.writeConfirmed(fact(factType = "a", key = "mid", valueJson = "3", confidence = 90))

        val reader = MemoryReadPolicy(store)
        val read = reader.read(MemoryScopeType.STORE, "STORE-1")
        assertEquals(3, read.size)
        // 前两条为确认记忆（100 在前），观察记忆最后
        assertEquals(MemorySource.USER_CONFIRMED, read[0].source)
        assertEquals(MemorySource.USER_CONFIRMED, read[1].source)
        assertEquals(MemorySource.OBSERVED_PATTERN, read[2].source)
    }

    @Test
    fun `Prompt 摘要：top-K 限制防全库复制`() {
        val p = policy(threshold = 1)
        p.writeConfirmed(fact(factType = "t", key = "k1", valueJson = "1"))
        p.writeConfirmed(fact(factType = "t", key = "k2", valueJson = "2"))
        p.writeConfirmed(fact(factType = "t", key = "k3", valueJson = "3"))

        val reader = MemoryReadPolicy(store)
        val prompt = reader.forPrompt(MemoryScopeType.STORE, "STORE-1", limit = 2)
        assertEquals(2, prompt.split("\n").size)
        assertTrue(prompt.contains("t.k1=1"))
        assertTrue(prompt.contains("t.k2=2"))
        assertTrue(!prompt.contains("k3"))
    }
}
