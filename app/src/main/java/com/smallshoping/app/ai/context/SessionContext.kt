package com.smallshoping.app.ai.context

/**
 * 会话上下文（spec 06 §1：Session Context，秒级/分钟级工作记忆）。
 *
 * 只保存当前任务相关的最小引用（spec 06 §5），不累积聊天记录：
 * 当前订单、最近商品/客户/会员、最近意图与 Tool 结果摘要。
 *
 * 事实优先级：Ledger/DB Fact > 当前上下文 > 已确认记忆 > 推断记忆。
 * 本对象是「引用」不是「事实」，实体数据一律回查数据库。
 */
data class SessionContext(
    val deviceSessionId: String,
    val activeSaleOrderId: String? = null,
    val lastProductId: String? = null,
    val lastCustomerId: String? = null,
    val lastMemberId: String? = null,
    val lastIntent: String? = null,
    /** 最近业务对象引用与 Tool 结果摘要（键值对，保持最小） */
    val contextJson: Map<String, String> = emptyMap(),
    val expiresAtMillis: Long,
    val updatedAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(deviceSessionId.isNotBlank()) { "deviceSessionId 不能为空" }
        require(expiresAtMillis > 0) { "expiresAtMillis 必须为正" }
    }

    /** 滑动续期：更新 updatedAt 并将过期时间顺延 [ttlMillis]。 */
    fun refreshed(nowMillis: Long, ttlMillis: Long): SessionContext =
        copy(updatedAtMillis = nowMillis, expiresAtMillis = nowMillis + ttlMillis)

    val isExpired: Boolean get() = System.currentTimeMillis() >= expiresAtMillis
}
