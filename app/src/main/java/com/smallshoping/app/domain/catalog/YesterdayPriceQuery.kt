package com.smallshoping.app.domain.catalog

import java.time.Instant
import java.time.ZoneId

/**
 * 昨日售价查询（spec 06 §6「还是昨天那个价格」第 2 步）。
 *
 * 业务日期按店铺时区计算（spec 03 §6），只取昨天业务日内的
 * SALE 类型价格历史；多条记录时由上层消歧，本类只回事实不猜测。
 */
class YesterdayPriceQuery(
    private val products: ProductRepository,
    private val timeZoneId: String = "Asia/Shanghai",
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** 昨天业务日内的售价历史（按时间序）。 */
    fun yesterdaySalePrices(productId: String): List<PriceHistoryEntry> {
        val zone = ZoneId.of(timeZoneId)
        val today = Instant.ofEpochMilli(clock()).atZone(zone).toLocalDate()
        val yesterday = today.minusDays(1)
        val startMillis = yesterday.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        return products.priceHistory(productId).filter {
            it.priceType == PriceType.SALE &&
                it.createdAtMillis in startMillis until endMillis
        }
    }
}
