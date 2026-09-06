package com.smallshoping.app.domain.report

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.domain.sales.SaleOrder
import com.smallshoping.app.domain.sales.SaleRepository
import com.smallshoping.app.domain.sales.SaleStatus

/**
 * 客户历史（Task 035）：从销售事实聚合，不写长期记忆——
 * 结构化账务事实以数据库为准（spec 06 §2）。
 */
class CustomerHistory(private val sales: SaleRepository) {

    /** 客户的完成单（按完成时间序）。 */
    fun completedSales(customerId: String): List<SaleOrder> =
        sales.allSales().filter {
            it.customerId == customerId && it.status == SaleStatus.COMPLETED
        }.sortedBy { it.completedAtMillis }

    /** 客户累计消费（完成单总额之和）。 */
    fun totalSpentMinor(customerId: String): Long =
        completedSales(customerId).fold(0L) { acc, sale -> Math.addExact(acc, sale.total.minor) }

    /** 客户购买过的商品（按购买次数降序，含累计金额）。 */
    fun topProducts(customerId: String, limit: Int = 10): List<ProductPurchaseSummary> {
        require(limit > 0) { "limit 必须为正" }
        data class Acc(val name: String, var count: Int, var amountMinor: Long)
        val byProduct = LinkedHashMap<String, Acc>()
        for (sale in completedSales(customerId)) {
            for (item in sale.items) {
                val acc = byProduct.getOrPut(item.productId) { Acc(item.productName, 0, 0L) }
                acc.count += 1
                acc.amountMinor = Math.addExact(acc.amountMinor, item.subtotal.minor)
            }
        }
        return byProduct.values
            .sortedWith(compareByDescending<Acc> { it.count }.thenByDescending { it.amountMinor })
            .take(limit)
            .map { ProductPurchaseSummary(it.name, it.count, Money(it.amountMinor)) }
    }
}

/** 客户购买商品聚合（名称、次数、累计金额）。 */
data class ProductPurchaseSummary(
    val productName: String,
    val purchaseCount: Int,
    val totalAmount: Money
)
