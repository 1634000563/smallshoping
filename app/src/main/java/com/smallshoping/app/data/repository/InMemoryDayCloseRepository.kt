package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.report.CloseOutcome
import com.smallshoping.app.domain.report.DayClose
import com.smallshoping.app.domain.report.DayCloseRepository
import java.time.LocalDate

/**
 * 内存日结实现：线程安全；同一业务日只结一次（幂等）。
 */
class InMemoryDayCloseRepository(
    /** 写穿钩子（Task 059 SQLite 持久化）；null 时纯内存。 */
    private val persist: com.smallshoping.app.data.sqlite.DayClosePersistence? = null
) : DayCloseRepository {

    private val lock = Any()
    private val byDate = LinkedHashMap<LocalDate, DayClose>()

    override fun close(dayClose: DayClose): CloseOutcome = synchronized(lock) {
        byDate[dayClose.businessDate]?.let { return CloseOutcome.AlreadyClosed(it) }
        byDate[dayClose.businessDate] = dayClose
        persist?.onDayClose(dayClose)
        CloseOutcome.Closed(dayClose)
    }

    override fun byDate(businessDate: LocalDate): DayClose? = synchronized(lock) {
        byDate[businessDate]
    }

    override fun all(): List<DayClose> = synchronized(lock) {
        byDate.values.toList()
    }

    /** 数据擦除（spec 13 §5，仅 DataWipeService 调用）。 */
    fun wipe() = synchronized(lock) {
        byDate.clear()
    }
}
