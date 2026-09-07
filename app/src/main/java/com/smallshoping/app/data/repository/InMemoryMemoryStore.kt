package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.memory.MemoryFact
import com.smallshoping.app.domain.memory.MemoryScopeType
import com.smallshoping.app.domain.memory.MemoryStore

/**
 * 内存长期记忆实现：线程安全。
 *
 * 语义基线同 [com.smallshoping.app.data.ledger.InMemoryLedger]：
 * 真实持久化实现（Room/SQLite）须通过同一组测试。
 */
class InMemoryMemoryStore(
    /** 写穿钩子（Task 059 SQLite 持久化）；null 时纯内存。 */
    private val persist: com.smallshoping.app.data.sqlite.MemoryPersistence? = null
) : MemoryStore {

    private val lock = Any()

    /** （作用域+类型+键）→ 事实；键唯一，重复写入即更新。 */
    private val byKey = LinkedHashMap<MemoryKey, MemoryFact>()
    private val byId = LinkedHashMap<String, MemoryFact>()

    private data class MemoryKey(
        val scopeType: MemoryScopeType,
        val scopeId: String,
        val factType: String,
        val key: String
    )

    override fun upsert(fact: MemoryFact): MemoryFact = synchronized(lock) {
        val k = MemoryKey(fact.scopeType, fact.scopeId, fact.factType, fact.key)
        val existing = byKey[k]
        val stored = if (existing == null) {
            fact
        } else {
            fact.copy(
                id = existing.id,
                createdAtMillis = existing.createdAtMillis,
                updatedAtMillis = System.currentTimeMillis(),
                active = true
            )
        }
        byKey[k] = stored
        byId[stored.id] = stored
        persist?.onUpsert(stored)
        stored
    }

    override fun query(scopeType: MemoryScopeType, scopeId: String): List<MemoryFact> =
        synchronized(lock) {
            byKey.values.filter { it.scopeType == scopeType && it.scopeId == scopeId && it.active }
        }

    override fun queryByType(
        scopeType: MemoryScopeType,
        scopeId: String,
        factType: String
    ): List<MemoryFact> = synchronized(lock) {
        byKey.values.filter {
            it.scopeType == scopeType && it.scopeId == scopeId &&
                it.factType == factType && it.active
        }
    }

    override fun findByKey(
        scopeType: MemoryScopeType,
        scopeId: String,
        factType: String,
        key: String
    ): MemoryFact? = synchronized(lock) {
        byKey[MemoryKey(scopeType, scopeId, factType, key)]?.takeIf { it.active }
    }

    override fun deactivate(id: String): Boolean = synchronized(lock) {
        val fact = byId[id] ?: return false
        val updated = fact.copy(active = false, updatedAtMillis = System.currentTimeMillis())
        byId[id] = updated
        byKey[MemoryKey(updated.scopeType, updated.scopeId, updated.factType, updated.key)] = updated
        persist?.onUpsert(updated)
        true
    }

    override fun all(): List<MemoryFact> = synchronized(lock) {
        byId.values.toList()
    }
}
