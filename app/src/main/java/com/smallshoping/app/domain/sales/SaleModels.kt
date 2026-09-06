package com.smallshoping.app.domain.sales

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Quantity

/** 销售单状态（spec 08 §3 最小子集：DRAFT/COMPLETED/CANCELLED）。 */
enum class SaleStatus { DRAFT, COMPLETED, CANCELLED }

/** V1 支付方式：现金/微信/支付宝手工确认 + 会员余额（会员扣款 Task 022 接入）。 */
enum class PaymentMethod { CASH, WECHAT, ALIPAY, MEMBER }

/**
 * 销售行：价格一律取商品目录快照（Domain 计算），绝不采用 AI 给出的金额
 * （AI 宪法：AI 不负责最终金额计算）。
 */
data class SaleItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val productId: String,
    val productName: String,
    val quantity: Quantity,
    /** 每 1 个 quantity.unit 的单价快照 */
    val unitPrice: Money,
    val subtotal: Money
) {

    init {
        require(!unitPrice.isNegative) { "单价不能为负：$unitPrice" }
        require(!subtotal.isNegative) { "行小计不能为负：$subtotal" }
    }
}

/**
 * 销售单。total 是冗余快照，任何使用处都可与 items 重算校验（数据宪法 #11）。
 */
data class SaleOrder(
    val id: String,
    val storeId: String,
    val items: List<SaleItem> = emptyList(),
    val status: SaleStatus = SaleStatus.DRAFT,
    val paymentMethod: PaymentMethod? = null,
    val total: Money = Money.ZERO,
    val checkoutIdempotencyKey: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val completedAtMillis: Long? = null
) {

    init {
        require(storeId.isNotBlank()) { "store_id 不能为空" }
    }

    fun computeTotal(): Money = items.fold(Money.ZERO) { acc, item -> acc + item.subtotal }
}
