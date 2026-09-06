package com.smallshoping.app.data.repository

import com.smallshoping.app.ai.context.SessionContext
import com.smallshoping.app.ai.context.SessionContextStore

/**
 * 内存会话上下文实现：线程安全，自动过滤过期会话。
 */
class InMemorySessionContextStore : SessionContextStore {

    private val lock = Any()
    private val bySession = LinkedHashMap<String, SessionContext>()

    override fun load(deviceSessionId: String): SessionContext? = synchronized(lock) {
        bySession[deviceSessionId]?.takeUnless { it.isExpired }
    }

    override fun save(context: SessionContext) {
        synchronized(lock) {
            bySession[context.deviceSessionId] = context
        }
    }

    override fun clear(deviceSessionId: String) {
        synchronized(lock) {
            bySession.remove(deviceSessionId)
        }
    }

    override fun clearExpired(nowMillis: Long) {
        synchronized(lock) {
            bySession.entries.removeIf { nowMillis >= it.value.expiresAtMillis }
        }
    }
}
