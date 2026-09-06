package com.smallshoping.app.domain.report

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity
import com.smallshoping.app.domain.sales.SaleRepository
import com.smallshoping.app.domain.sales.SaleStatus

/**
 * 商品历史（Task 035）：某商品的全部销售事件（时间、数量、单价快照、单号），
 * 从销售事实聚合，供报价/补货参考；只读，不写记忆。
 */
class ProductHistory(private val sales: SaleRepository) {

    /** 某商品的一次销售事件。 */
    data class ProductSaleEvent(
        val saleId: String,
        val quantity: Quantity,
        /** 成交单价快照（分）。 */
        val unitPriceMinor: Long,
        val subtotalMinor: Long,
        val soldAtMillis: Long?
    )

    /** 商品销售事件（按销售完成时间序）。 */
    fun saleEvents(productId: String): List<ProductSaleEvent> =
        sales.allSales()
            .filter { it.status == SaleStatus.COMPLETED }
            .sortedBy { it.completedAtMillis }
            .flatMap { sale ->
                sale.items.filter { it.productId == productId }.map { item ->
                    ProductSaleEvent(
                        saleId = sale.id,
                        quantity = item.quantity,
                        unitPriceMinor = item.unitPrice.minor,
                        subtotalMinor = item.subtotal.minor,
                        soldAtMillis = sale.completedAtMillis
                    )
                }
            }

    /** 商品最近成交单价快照；从未卖出返回 null。 */
    fun latestSalePriceMinor(productId: String): Long? =
        saleEvents(productId).lastOrNull()?.unitPriceMinor

    /** 商品累计销售数量（基本单位刻度）。 */
    fun totalSoldScaled(productId: String): Long =
        saleEvents(productId).fold(0L) { acc, e -> Math.addExact(acc, e.quantity.scaled) }

    /** 商品累计销售额（分）。 */
    fun totalRevenueMinor(productId: String): Long =
        saleEvents(productId).fold(0L) { acc, e -> Math.addExact(acc, e.subtotalMinor) }
}
