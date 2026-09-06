package com.smallshoping.app.domain.report

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.data.ledger.InMemoryLedger
import com.smallshoping.app.data.repository.InMemorySaleRepository
import com.smallshoping.app.domain.sales.PaymentMethod
import com.smallshoping.app.domain.sales.SaleOrder
import com.smallshoping.app.domain.sales.SaleStatus
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class TodaySalesSummaryTest {

    private val ledger = InMemoryLedger()
    private val sales = InMemorySaleRepository(ledger)

    // 2026-09-06 00:00 Asia/Shanghai = 2026-09-05 16:00 UTC
    private val dayStartMillis = java.time.LocalDate.of(2026, 9, 6)
        .atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli()

    private var now = dayStartMillis + 10 * 3600_000L // 当天上午 10 点

    private val summary = TodaySalesSummary(
        sales = sales,
        timeZoneId = "Asia/Shanghai",
        clock = { now }
    )

    private fun completedSale(id: String, totalMinor: Long, completedAt: Long) =
        SaleOrder(
            id = id,
            storeId = "STORE-1",
            status = SaleStatus.COMPLETED,
            paymentMethod = PaymentMethod.CASH,
            total = Money(totalMinor),
            checkoutIdempotencyKey = "ck-$id",
            completedAtMillis = completedAt
        )

    @Test
    fun `正常路径：只统计今日已结账单`() {
        sales.saveDraft(completedSale("S-1", 760, dayStartMillis + 3600_000L))
        sales.saveDraft(completedSale("S-2", 240, dayStartMillis + 7200_000L))
        sales.saveDraft(SaleOrder(id = "S-DRAFT", storeId = "STORE-1", total = Money(999))) // 草稿不计
        val s = summary.today()
        assertEquals(Money(1000), s.total)
        assertEquals(2, s.count)
        assertEquals(java.time.LocalDate.of(2026, 9, 6), s.businessDate)
    }

    @Test
    fun `边界输入：昨日与明日的单不计入今日`() {
        sales.saveDraft(completedSale("S-Y", 500, dayStartMillis - 1L))
        sales.saveDraft(completedSale("S-T", 600, dayStartMillis + 24 * 3600_000L))
        val s = summary.today()
        assertEquals(0, s.count)
        assertEquals(Money.ZERO, s.total)
    }

    @Test
    fun `边界输入：当天无销售时结果为零`() {
        val s = summary.today()
        assertEquals(Money.ZERO, s.total)
        assertEquals(0, s.count)
    }

    @Test
    fun `营业日切换：23 点 59 分与次日 0 点 01 分分属两天`() {
        val late = dayStartMillis + 23 * 3600_000L + 59 * 60_000L
        val earlyNext = dayStartMillis + 24 * 3600_000L + 60_000L
        sales.saveDraft(completedSale("S-LATE", 100, late))
        sales.saveDraft(completedSale("S-EARLY", 200, earlyNext))
        now = late
        assertEquals(Money(100), summary.today().total)
        now = earlyNext
        assertEquals(Money(200), summary.today().total)
    }
}
