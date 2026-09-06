package com.smallshoping.app.domain.ledger

/**
 * 账本端口（Domain 侧契约，数据宪法 #4/#5/#6）。
 *
 * - 只追加，不修改、不删除历史流水；
 * - 余额是可由流水重建的派生结果；
 * - 所有写入口带幂等键。
 *
 * Data 层（Room/SQLite）实现必须保持同样语义：真实事务 + 幂等去重 + 可重建。
 * AI 与 UI 不得直接持有账本，只能通过 Domain UseCase 触发写入。
 */
interface Ledger {

    /** 追加单条流水；同范围同幂等键重复追加返回 [AppendResult.Duplicate]，不重复记账。 */
    fun append(entry: LedgerEntry): AppendResult

    /** 原子批量追加：任一流水幂等键冲突则整批不生效（模拟事务回滚）。 */
    fun transact(entries: List<LedgerEntry>): List<AppendResult>

    /** 范围内全部流水（按追加顺序）。 */
    fun entries(scope: LedgerScope): List<LedgerEntry>

    /** 当前余额（由流水派生的快照，允许有缓存实现）。 */
    fun balance(scope: LedgerScope): Long

    /** 全部已建账范围（崩溃恢复/重建校验遍历用，Task 045）。 */
    fun allScopes(): Set<LedgerScope>

    /**
     * 从流水重算余额并与现有值核对（spec 04 §13）。
     * 不一致时抛 [DataIntegrityException]，绝不静默覆盖。
     */
    fun rebuildBalance(scope: LedgerScope): Long
}

sealed interface AppendResult {
    data class Appended(val entry: LedgerEntry) : AppendResult
    data class Duplicate(val original: LedgerEntry) : AppendResult
}
