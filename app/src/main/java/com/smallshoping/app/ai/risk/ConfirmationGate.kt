package com.smallshoping.app.ai.risk

import com.smallshoping.app.ai.orchestrator.Intent
import java.util.UUID

/**
 * 操作确认状态机（spec 08 §9）：
 * PROPOSED → WAITING_CONFIRM → CONFIRMED / REJECTED / EXPIRED。
 *
 * 硬约束：老板说「是/确定/执行」只能确认**最近一个**处于 WAITING_CONFIRM
 * 的请求，并校验 request_id，防止确认错对象。
 */
class ConfirmationGate(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class Pending(
        val requestId: String,
        val intent: Intent,
        val createdAtMillis: Long
    )

    private val waiting = LinkedHashMap<String, Pending>()

    /** 提出待确认请求；已有相同 requestId 时幂等返回原请求。 */
    fun propose(intent: Intent): Pending {
        val requestId = intent.requestId ?: UUID.randomUUID().toString()
        waiting[requestId]?.let { return it }
        val pending = Pending(requestId, intent, clock())
        waiting[requestId] = pending
        return pending
    }

    /** 确认：只接受最近一个待确认请求，且必须校验 requestId。 */
    fun confirm(requestId: String): Intent? {
        expire()
        val latest = waiting.values.lastOrNull() ?: return null
        if (latest.requestId != requestId) return null
        waiting.remove(requestId)
        return latest.intent
    }

    /** 拒绝：同样只接受最近一个待确认请求。 */
    fun reject(requestId: String): Boolean {
        expire()
        val latest = waiting.values.lastOrNull() ?: return false
        if (latest.requestId != requestId) return false
        waiting.remove(requestId)
        return true
    }

    /** 清理过期请求。 */
    fun expire() {
        val now = clock()
        waiting.entries.removeIf { now - it.value.createdAtMillis >= ttlMillis }
    }

    fun latestWaiting(): Pending? {
        expire()
        return waiting.values.lastOrNull()
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 60_000L
    }
}
