package com.smallshoping.app.data.repository

import com.smallshoping.app.ai.context.DisambiguationStore
import com.smallshoping.app.ai.context.PendingQuestion

/**
 * 内存消歧问题存储：线程安全，单槽位，读取时按过期时间自动失效。
 */
class InMemoryDisambiguationStore(
    private val now: () -> Long = System::currentTimeMillis
) : DisambiguationStore {

    private val lock = Any()
    private val byDevice = HashMap<String, PendingQuestion>()

    override fun pending(deviceId: String): PendingQuestion? = synchronized(lock) {
        val question = byDevice[deviceId] ?: return null
        if (question.expiresAtMillis <= now()) {
            byDevice.remove(deviceId)
            return null
        }
        question
    }

    override fun propose(deviceId: String, question: PendingQuestion) = synchronized(lock) {
        byDevice[deviceId] = question
    }

    override fun clear(deviceId: String) {
        synchronized(lock) { byDevice.remove(deviceId) }
    }
}
