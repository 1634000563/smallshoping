package com.smallshoping.app.ai.tools

import com.smallshoping.app.domain.report.TodaySalesSummary

/**
 * get_today_sales：今天卖了多少钱（read，LOW）。
 * 口径严格按业务日期（店铺时区）+ 已结账销售单，由 Domain 汇总。
 */
class GetTodaySalesHandler(private val summary: TodaySalesSummary) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val s = summary.today()
        return mapOf(
            "status" to "OK",
            "total_minor" to s.total.minor.toString(),
            "count" to s.count.toString(),
            "message" to "今天（${s.businessDate}）共卖了 ${s.count} 笔，合计 ${s.total.minor} 分"
        )
    }
}
