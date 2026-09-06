package com.smallshoping.app.domain.security

import com.smallshoping.app.domain.journal.CommandJournal
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.report.DayCloseRepository

/**
 * 数据擦除（spec 13 §5）：用户可导出并删除本地数据。
 *
 * 擦除目标 = 账务事实（流水/销售单/支付记录/日结/命令日志），
 * 擦除后余额为零、流水为空，事实不可重建；
 * 历史交易删除默认受保护——普通 AI 指令不能触发（Task 040 拒绝删除语句），
 * 本服务是唯一的受控入口。
 */
class DataWipeService(
    private val ledger: Ledger,
    private val journal: CommandJournal,
    private val wipeSales: () -> Unit,
    private val wipePayments: () -> Unit,
    private val wipeDayCloses: () -> Unit
) {

    data class WipeReport(
        val ledgerEntriesBefore: Int,
        val journalRecordsBefore: Int
    )

    /** 执行擦除，返回擦除前的规模（供确认与审计展示）。 */
    fun wipe(): WipeReport {
        val report = WipeReport(
            ledgerEntriesBefore = ledger.allScopes().sumOf { ledger.entries(it).size },
            journalRecordsBefore = journal.all().size
        )
        ledger.wipe()
        journal.clear()
        wipeSales()
        wipePayments()
        wipeDayCloses()
        return report
    }

    /** 擦除后校验：账本必须为空。 */
    fun verifyWiped(): Boolean =
        ledger.allScopes().isEmpty() && journal.all().isEmpty()
}

/**
 * 日志/展示脱敏（spec 13 §7）：手机号打码（138****0000）。
 */
object PhoneMasker {

    fun mask(phone: String?): String? {
        if (phone.isNullOrBlank()) return phone
        if (phone.length < 7) return "***"
        return phone.substring(0, 3) + "****" + phone.substring(phone.length - 4)
    }
}
