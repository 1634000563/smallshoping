package com.smallshoping.app.data.ledger

import com.smallshoping.app.domain.ledger.AppendResult
import com.smallshoping.app.domain.ledger.DataIntegrityException
import com.smallshoping.app.domain.ledger.IdempotencyConflictException
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope

/**
 * 内存账本实现：线程安全、只追加、幂等、原子批量。
 *
 * 用途：
 * 1. 单元测试与后续 Task 的 Fake 实现；
 * 2. 定义 Room/SQLite 实现的语义基线（幂等去重、批次回滚、重建校验），
 *    真实持久化版（Task 006 起随 Data 层落地）必须通过同一组测试。
 *
 * 余额采用「缓存 + 可重建校验」结构：
 * [balance] 返回缓存值，[rebuildBalance] 从流水重算并核对，
 * 不一致抛 [DataIntegrityException]（spec 04 §13，不静默覆盖）。
 */
class InMemoryLedger(
    /** 写穿钩子（Task 059 SQLite 持久化）：追加成功后同步落盘；null 时纯内存。 */
    private val persistAppend: ((LedgerEntry) -> Unit)? = null,
    private val persistWipe: (() -> Unit)? = null
) : Ledger {

    private val lock = Any()
    private val entriesByScope = LinkedHashMap<LedgerScope, MutableList<LedgerEntry>>()
    private val keysByScope = HashMap<LedgerScope, MutableSet<IdempotencyKey>>()
    private val cachedBalances = HashMap<LedgerScope, Long>()

    override fun append(entry: LedgerEntry): AppendResult = synchronized(lock) {
        val keys = keysByScope.getOrPut(entry.scope) { HashSet() }
        if (!keys.add(entry.idempotencyKey)) {
            val original = entriesByScope.getValue(entry.scope)
                .first { it.idempotencyKey == entry.idempotencyKey }
            return AppendResult.Duplicate(original)
        }
        entriesByScope.getOrPut(entry.scope) { ArrayList() }.add(entry)
        cachedBalances[entry.scope] =
            Math.addExact(cachedBalances.getOrDefault(entry.scope, 0L), entry.delta)
        persistAppend?.invoke(entry)
        AppendResult.Appended(entry)
    }

    override fun transact(entries: List<LedgerEntry>): List<AppendResult> = synchronized(lock) {
        // 先整体校验幂等键，任一冲突则整批拒绝（事务回滚语义）
        val conflicts = LinkedHashSet<IdempotencyKey>()
        val seen = HashSet<IdempotencyKey>()
        for (e in entries) {
            val existing = keysByScope[e.scope]
            if (!seen.add(e.idempotencyKey) || (existing != null && existing.contains(e.idempotencyKey))) {
                conflicts.add(e.idempotencyKey)
            }
        }
        if (conflicts.isNotEmpty()) {
            throw IdempotencyConflictException(conflicts)
        }
        entries.map { append(it) }
    }

    override fun entries(scope: LedgerScope): List<LedgerEntry> = synchronized(lock) {
        entriesByScope[scope]?.toList() ?: emptyList()
    }

    override fun allScopes(): Set<LedgerScope> = synchronized(lock) {
        entriesByScope.keys.toSet()
    }

    override fun balance(scope: LedgerScope): Long = synchronized(lock) {
        cachedBalances[scope] ?: 0L
    }

    override fun rebuildBalance(scope: LedgerScope): Long = synchronized(lock) {
        val rebuilt = entries(scope).fold(0L) { sum, e -> Math.addExact(sum, e.delta) }
        val cached = cachedBalances[scope] ?: 0L
        if (rebuilt != cached) {
            throw DataIntegrityException(scope, rebuilt, cached)
        }
        rebuilt
    }

    /** 仅测试用：故意破坏缓存余额，验证重建校验能发现且不静默覆盖。 */
    internal fun debugCorruptCachedBalance(scope: LedgerScope, value: Long) = synchronized(lock) {
        cachedBalances[scope] = value
    }

    override fun wipe() = synchronized(lock) {
        entriesByScope.clear()
        keysByScope.clear()
        cachedBalances.clear()
        persistWipe?.invoke()
        Unit
    }
}
