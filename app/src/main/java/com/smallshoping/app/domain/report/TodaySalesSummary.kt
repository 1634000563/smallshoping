package com.smallshoping.app.domain.report

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.domain.sales.SaleRepository
import com.smallshoping.app.domain.sales.SaleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 今日销售汇总（spec 03 §6）：业务日期按店铺时区计算，
 * 不能直接用设备 UTC 日期；只统计 COMPLETED 单，草稿/取消不计。
 */
class TodaySalesSummary(
    private val sales: SaleRepository,
    private val timeZoneId: String = "Asia/Shanghai",
    private val clock: () -> Long = System::currentTimeMillis
) {

    data class Summary(
        val businessDate: LocalDate,
        val total: Money,
        val count: Int
    )

    fun today(): Summary {
        val zone = ZoneId.of(timeZoneId)
        val instant = Instant.ofEpochMilli(clock())
        val businessDate = instant.atZone(zone).toLocalDate()
        val startMillis = businessDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = businessDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val completed = sales.allSales().filter {
            it.status == SaleStatus.COMPLETED &&
                it.completedAtMillis != null &&
                it.completedAtMillis in startMillis until endMillis
        }
        val total = completed.fold(Money.ZERO) { acc, sale -> acc + sale.total }
        return Summary(businessDate, total, completed.size)
    }
}
