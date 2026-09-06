package com.smallshoping.app.domain.memory

/**
 * 长期记忆存储端口（Domain 侧契约，spec 06）。
 *
 * 同一（scopeType, scopeId, factType, key）只有一行：
 * 重复写入视为更新（覆盖值与置信度、刷新确认时间），不产生重复行。
 * 停用不删除历史（可撤销记忆，spec 06 §1 Customer Memory）。
 *
 * 写入策略（何时允许写）由 Task 024 的 Memory Write 策略负责，
 * 本端口只负责存储语义，不判断事实优先级。
 */
interface MemoryStore {

    /** 新增或更新一条事实；返回落库后的完整事实。 */
    fun upsert(fact: MemoryFact): MemoryFact

    /** 某作用域下全部活跃事实（按写入顺序）。 */
    fun query(scopeType: MemoryScopeType, scopeId: String): List<MemoryFact>

    /** 某作用域某类型下的活跃事实。 */
    fun queryByType(scopeType: MemoryScopeType, scopeId: String, factType: String): List<MemoryFact>

    /** 按（作用域+类型+键）精确查找活跃事实。 */
    fun findByKey(scopeType: MemoryScopeType, scopeId: String, factType: String, key: String): MemoryFact?

    /** 停用（active=false），历史保留、可再查全部（备份/导出用）。 */
    fun deactivate(id: String): Boolean

    /** 全部事实（含停用），供备份与迁移。 */
    fun all(): List<MemoryFact>
}
