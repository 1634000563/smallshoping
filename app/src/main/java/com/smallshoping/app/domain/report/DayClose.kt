package com.smallshoping.app.domain.report

import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.payment.PaymentRepository
import com.smallshoping.app.domain.payment.PaymentStatus
import com.smallshoping.app.domain.sales.PaymentMethod
import com.smallshoping.app.domain.sales.SaleRepository
import com.smallshoping.app.domain.sales.SaleStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 日结状态。 */
enum class DayCloseStatus { CLOSED }

/**
 * 日结快照（spec 03 day_close 表）：
 * 不修改历史销售，只生成核对快照（spec 04 §10）；
 * 差异写入 [varianceMinor] 并保留说明，可审计。
 */
data class DayClose(
    val id: String = UUID.randomUUID().toString(),
    val businessDate: LocalDate,
    val openedAtMillis: Long,
    val closedAtMillis: Long,
    val cashExpectedMinor: Long,
    val cashActualMinor: Long,
    val varianceMinor: Long,
    val status: DayCloseStatus = DayCloseStatus.CLOSED,
    val note: String = ""
) {

    init {
        require(cashExpectedMinor >= 0) { "现金应收不能为负" }
        require(cashActualMinor >= 0) { "现金实收不能为负" }
    }
}

/** 日结仓库端口：同一业务日只能结一次（幂等）。 */
interface DayCloseRepository {

    /** 保存日结；同业务日重复提交返回 [CloseOutcome.AlreadyClosed]。 */
    fun close(dayClose: DayClose): CloseOutcome

    /** 按业务日查日结；无返回 null。 */
    fun byDate(businessDate: LocalDate): DayClose?

    /** 全部日结（按日期序）。 */
    fun all(): List<DayClose>
}

sealed interface CloseOutcome {
    data class Closed(val dayClose: DayClose) : CloseOutcome
    data class AlreadyClosed(val dayClose: DayClose) : CloseOutcome
}

/**
 * 日结与经营核对（Task 048，spec 02 §7 / spec 04 §10）：
 *
 * 现金应收 = 当日完成销售中已确认（CONFIRMED）的现金支付之和；
 * 微信/支付宝/会员消费/客户赊账分列展示供核对；
 * 日结只生成快照，绝不修改历史销售；差异（实收-应收）如实记录。
 */
class DayCloseService(
    private val sales: SaleRepository,
    private val payments: PaymentRepository,
    private val dayCloses: DayCloseRepository,
    private val ledger: Ledger? = null,
    private val timeZoneId: String = "Asia/Shanghai",
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 某业务日的核对汇总。 */
    data class CheckSummary(
        val businessDate: LocalDate,
        val cashMinor: Long,
        val wechatMinor: Long,
        val alipayMinor: Long,
        val memberMinor: Long,
        val customerCreditMinor: Long
    )

    /** 当日（或指定业务日）经营核对：按支付方式分列。 */
    fun checkSummary(businessDate: LocalDate = today()): CheckSummary {
        val zone = ZoneId.of(timeZoneId)
        val start = businessDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = businessDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val completedIds = sales.allSales()
            .filter {
                it.status == SaleStatus.COMPLETED &&
                    it.completedAtMillis != null && it.completedAtMillis in start until end
            }
            .map { it.id }
            .toSet()
        var cash = 0L
        var wechat = 0L
        var alipay = 0L
        var member = 0L
        var credit = 0L
        for (payment in payments.all()) {
            if (payment.saleOrderId !in completedIds) continue
            if (payment.status != PaymentStatus.CONFIRMED) continue
            when (payment.method) {
                PaymentMethod.CASH -> cash = Math.addExact(cash, payment.amountMinor)
                PaymentMethod.WECHAT -> wechat = Math.addExact(wechat, payment.amountMinor)
                PaymentMethod.ALIPAY -> alipay = Math.addExact(alipay, payment.amountMinor)
                PaymentMethod.MEMBER -> member = Math.addExact(member, payment.amountMinor)
            }
        }
        // 客户赊账 = 该日 CUSTOMER_CREDIT 流水（欠款事实，spec 04 §7）
        ledger?.let { l ->
            for (scope in l.allScopes()) {
                if (scope.type != LedgerScopeType.CUSTOMER) continue
                for (entry in l.entries(scope)) {
                    if (entry.movementType == MovementType.CUSTOMER_CREDIT &&
                        entry.createdAtMillis in start until end
                    ) {
                        credit = Math.addExact(credit, entry.delta)
                    }
                }
            }
        }
        return CheckSummary(businessDate, cash, wechat, alipay, member, credit)
    }

    /**
     * 日结：老板点完现金后生成快照。
     * 差异 = 实收 - 应收；不修改任何历史销售（spec 04 §10）。
     */
    fun closeDay(cashActualMinor: Long, note: String = ""): CloseOutcome {
        val date = today()
        val summary = checkSummary(date)
        val startMillis = date.atStartOfDay(ZoneId.of(timeZoneId)).toInstant().toEpochMilli()
        val dayClose = DayClose(
            businessDate = date,
            openedAtMillis = startMillis,
            closedAtMillis = clock(),
            cashExpectedMinor = summary.cashMinor,
            cashActualMinor = cashActualMinor,
            varianceMinor = Math.subtractExact(cashActualMinor, summary.cashMinor),
            note = note
        )
        return dayCloses.close(dayClose)
    }

    /** 今日业务日（店铺时区，spec 03 §6）。 */
    fun today(): LocalDate =
        Instant.ofEpochMilli(clock()).atZone(ZoneId.of(timeZoneId)).toLocalDate()
}
