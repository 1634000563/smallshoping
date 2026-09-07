package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.journal.CommandJournal
import com.smallshoping.app.domain.journal.CommandRecord

/**
 * 内存命令日志：线程安全，只追加。
 * 真实持久化实现（Room/SQLite，Task 046）须通过同一组语义测试。
 */
class InMemoryCommandJournal(
    /** 写穿钩子（Task 059 SQLite 持久化）；null 时纯内存。 */
    private val persist: com.smallshoping.app.data.sqlite.JournalPersistence? = null
) : CommandJournal {

    private val lock = Any()
    private val records = ArrayList<CommandRecord>()

    override fun append(record: CommandRecord) {
        persist?.onAppend(record)
        synchronized(lock) { records.add(record) }
    }

    override fun all(): List<CommandRecord> = synchronized(lock) {
        records.toList()
    }

    override fun clear() = synchronized(lock) {
        persist?.onClear()
        records.clear()
    }
}
