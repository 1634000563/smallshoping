package com.smallshoping.app.domain.memory

/**
 * 记忆写入策略（spec 06 §3）：只有两类情况允许写长期记忆——
 * 1. 用户明确说「以后都这样」或确认系统建议 → [writeConfirmed]（USER_CONFIRMED，置信度 100）
 * 2. 可重复观察且达到阈值的稳定模式 → [observe]（OBSERVED_PATTERN，置信度 60）
 *
 * 事实优先级（spec 06 §2）：推断记忆不得覆盖已确认记忆，
 * 观察到已确认事实存在时直接拒绝写入。
 */
class MemoryWritePolicy(
    private val store: MemoryStore,
    /** 观察次数阈值：达到即写 OBSERVED_PATTERN。 */
    private val observationThreshold: Int = 3,
    /** 观察时间窗：超过窗外的观察不计入稳定模式。 */
    private val observationWindowMillis: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis
) {

    /** 观察记录：按（作用域+类型+键）保留时间窗内的时间戳。 */
    private val lock = Any()
    private val observations = HashMap<MemoryKey, ArrayDeque<Long>>()

    private data class MemoryKey(
        val scopeType: MemoryScopeType,
        val scopeId: String,
        val factType: String,
        val key: String
    )

    sealed interface MemoryWriteResult {
        data class Written(val fact: MemoryFact) : MemoryWriteResult

        /** 观察次数未达阈值：暂不写。 */
        data class NeedMoreObservations(val observed: Int, val required: Int) : MemoryWriteResult

        /** 同一键已有已确认事实，推断记忆不得覆盖（spec 06 §2）。 */
        data object BlockedByConfirmedFact : MemoryWriteResult
    }

    /** 用户确认路径：直接写/刷新 USER_CONFIRMED 事实（置信度 100）。 */
    fun writeConfirmed(fact: MemoryFact): MemoryWriteResult {
        val stored = store.upsert(
            fact.copy(
                source = MemorySource.USER_CONFIRMED,
                confidence = 100,
                lastConfirmedAtMillis = now()
            )
        )
        return MemoryWriteResult.Written(stored)
    }

    /** 观察路径：时间窗内累计 [observationThreshold] 次才写 OBSERVED_PATTERN（置信度 60）。 */
    fun observe(fact: MemoryFact): MemoryWriteResult = synchronized(lock) {
        val key = MemoryKey(fact.scopeType, fact.scopeId, fact.factType, fact.key)
        // 已确认事实不可被推断覆盖
        val existing = store.findByKey(fact.scopeType, fact.scopeId, fact.factType, fact.key)
        if (existing != null && existing.source == MemorySource.USER_CONFIRMED) {
            return MemoryWriteResult.BlockedByConfirmedFact
        }

        val windowStart = now() - observationWindowMillis
        val deque = observations.getOrPut(key) { ArrayDeque() }
        while (deque.isNotEmpty() && deque.first() < windowStart) deque.removeFirst()
        deque.addLast(now())

        if (deque.size < observationThreshold) {
            return MemoryWriteResult.NeedMoreObservations(deque.size, observationThreshold)
        }
        observations.remove(key)
        val stored = store.upsert(
            fact.copy(
                source = MemorySource.OBSERVED_PATTERN,
                confidence = 60,
                lastConfirmedAtMillis = null
            )
        )
        MemoryWriteResult.Written(stored)
    }
}

/**
 * 记忆读取策略（spec 06 §2）：
 * 结构化账务事实以数据库为准（由调用方优先查询）；
 * 本类只负责记忆层读取——已确认记忆排在推断记忆之前，
 * 注入 Prompt 只取 top-K 摘要，禁止全库复制（Task 024 约束）。
 */
class MemoryReadPolicy(private val store: MemoryStore) {

    /** 某作用域全部活跃事实：USER_CONFIRMED 在前，同源按置信度降序。 */
    fun read(scopeType: MemoryScopeType, scopeId: String): List<MemoryFact> {
        val confirmed = ArrayList<MemoryFact>()
        val observed = ArrayList<MemoryFact>()
        for (fact in store.query(scopeType, scopeId)) {
            when (fact.source) {
                MemorySource.USER_CONFIRMED -> confirmed.add(fact)
                MemorySource.OBSERVED_PATTERN -> observed.add(fact)
            }
        }
        return confirmed.sortedByDescending { it.confidence } +
            observed.sortedByDescending { it.confidence }
    }

    /** 注入 Prompt 的 top-K 摘要（单行一条，防全库复制进上下文）。 */
    fun forPrompt(scopeType: MemoryScopeType, scopeId: String, limit: Int = 5): String {
        require(limit > 0) { "limit 必须为正" }
        return read(scopeType, scopeId)
            .take(limit)
            .joinToString("\n") {
                "${it.factType}.${it.key}=${it.valueJson}（置信 ${it.confidence}）"
            }
    }
}
