package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.memory.MemoryFact
import com.smallshoping.app.domain.memory.MemoryScopeType
import com.smallshoping.app.domain.memory.MemorySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 023：长期记忆存储语义基线。 */
class InMemoryMemoryStoreTest {

    private val store = InMemoryMemoryStore()

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
    fun `正常路径：写入后按作用域、类型、键查询`() {
        store.upsert(fact())
        assertEquals(1, store.query(MemoryScopeType.STORE, "STORE-1").size)
        assertEquals(
            1,
            store.queryByType(MemoryScopeType.STORE, "STORE-1", "default_unit").size
        )
        val found = store.findByKey(MemoryScopeType.STORE, "STORE-1", "default_unit", "weight")
        assertEquals("""{"unit":"jin"}""", found?.valueJson)
        // 作用域隔离
        assertTrue(store.query(MemoryScopeType.STORE, "STORE-2").isEmpty())
        assertTrue(store.query(MemoryScopeType.CUSTOMER, "STORE-1").isEmpty())
    }

    @Test
    fun `同键重复写入：更新而非新增行，保留原 id 与创建时间`() {
        val first = store.upsert(fact())
        val updated = store.upsert(
            fact(valueJson = """{"unit":"kg"}""", confidence = 100)
        )
        assertEquals(first.id, updated.id)
        assertEquals(first.createdAtMillis, updated.createdAtMillis)
        assertEquals(1, store.query(MemoryScopeType.STORE, "STORE-1").size)
        assertEquals("""{"unit":"kg"}""", updated.valueJson)
        assertEquals(100, updated.confidence)
    }

    @Test
    fun `停用：查询不可见但历史保留，可重新激活`() {
        val f = store.upsert(fact())
        assertTrue(store.deactivate(f.id))
        assertNull(store.findByKey(MemoryScopeType.STORE, "STORE-1", "default_unit", "weight"))
        assertTrue(store.query(MemoryScopeType.STORE, "STORE-1").isEmpty())
        // 历史保留（all 含停用）
        assertEquals(1, store.all().size)
        assertFalse(store.all()[0].active)
        // 同键再写入 = 重新激活同一行
        val reactivated = store.upsert(fact())
        assertEquals(f.id, reactivated.id)
        assertTrue(reactivated.active)
        assertFalse(store.deactivate("NOT-EXIST"))
    }

    @Test
    fun `多客户多类型：互不干扰`() {
        store.upsert(fact(scopeType = MemoryScopeType.CUSTOMER, scopeId = "C-1", factType = "usual_product", key = "top"))
        store.upsert(fact(scopeType = MemoryScopeType.CUSTOMER, scopeId = "C-2", factType = "usual_product", key = "top"))
        store.upsert(fact(scopeType = MemoryScopeType.STORE, scopeId = "STORE-1", factType = "price_habit", key = "round"))
        assertEquals(1, store.query(MemoryScopeType.CUSTOMER, "C-1").size)
        assertEquals(1, store.query(MemoryScopeType.CUSTOMER, "C-2").size)
        assertEquals(1, store.query(MemoryScopeType.STORE, "STORE-1").size)
        assertEquals(3, store.all().size)
    }
}
