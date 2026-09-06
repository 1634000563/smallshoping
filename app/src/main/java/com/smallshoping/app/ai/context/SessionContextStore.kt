package com.smallshoping.app.ai.context

/** 会话上下文默认存活时长：30 分钟（滑动续期）。 */
const val SESSION_TTL_MILLIS: Long = 30 * 60 * 1000L

/**
 * 会话上下文存储端口。
 *
 * 上下文是 AI 编排层的临时工作记忆，不是账务事实；
 * 数据实现负责持久化（V1 内存实现，Room 版随 Data 层落地）。
 */
interface SessionContextStore {

    fun load(deviceSessionId: String): SessionContext?

    fun save(context: SessionContext)

    fun clear(deviceSessionId: String)

    /** 清理过期会话（可定时调用；过期会话视为不存在）。 */
    fun clearExpired(nowMillis: Long = System.currentTimeMillis())
}
