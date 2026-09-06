package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.entityresolution.CustomerResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.money.MoneyParser
import com.smallshoping.app.domain.customer.CustomerDebtRequest
import com.smallshoping.app.domain.customer.CustomerDebtResult
import com.smallshoping.app.domain.customer.ReceiveCustomerPaymentUseCase
import com.smallshoping.app.domain.customer.RecordCustomerCreditUseCase
import com.smallshoping.app.domain.sales.CheckoutSaleRequest
import com.smallshoping.app.domain.sales.CheckoutSaleResult
import com.smallshoping.app.domain.sales.CheckoutSaleUseCase
import com.smallshoping.app.domain.sales.PaymentMethod
import com.smallshoping.app.domain.sales.SaleRepository

/** 共同实现：解析金额（分字符串或元/块毛文本），非正返回 null。 */
private fun parseAmount(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val fen = raw.toLongOrNull() ?: MoneyParser.parseYuanToMinor(raw) ?: return null
    return if (fen > 0) fen else null
}

/**
 * record_customer_credit：老张先记账 / 老张赊200（write，MEDIUM）。
 *
 * 金额缺省时取当前草稿单总额：先按现金结账完成销售（spec 17 路径 D
 * 「sale complete + customer ledger 增加应收」），再记欠款；
 * 显式金额只记欠款不动草稿单。幂等键按（客户+金额）生成。
 */
class RecordCustomerCreditHandler(
    private val customerResolver: CustomerResolver,
    private val credit: RecordCustomerCreditUseCase,
    private val checkout: CheckoutSaleUseCase,
    private val sales: SaleRepository,
    private val contexts: SessionContextStore,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val customerQuery = entities.getValue("customer")
        return when (val resolution = customerResolver.resolve(customerQuery)) {
            is Resolution.NotFound -> notFound(customerQuery)
            is Resolution.Ambiguous -> ambiguous(customerQuery, resolution, entities)
            is Resolution.Resolved -> {
                val customer = resolution.value
                // 金额：显式优先，否则草稿单总额（并顺带结账）
                val fen = parseAmount(entities["amount"]) ?: run {
                    val draftId = contexts.load(session.deviceId)?.activeSaleOrderId
                    val draft = draftId?.let { sales.findById(it) }
                    val draftTotal = draft?.total?.minor
                    if (draftTotal == null || draftTotal <= 0L) {
                        return mapOf(
                            "status" to "INVALID_ARGUMENT", "customer_id" to "",
                            "debt_after_minor" to "",
                            "message" to "没说要记多少，草稿单也是空的；请说金额（如：老张赊200）"
                        )
                    }
                    if (draft.status != com.smallshoping.app.domain.sales.SaleStatus.COMPLETED) {
                        when (checkout(
                            CheckoutSaleRequest(draft.id, PaymentMethod.CASH, "checkout:${draft.id}:credit")
                        )) {
                            is CheckoutSaleResult.Success, is CheckoutSaleResult.AlreadyCompleted -> Unit
                            is CheckoutSaleResult.SaleNotFound -> return mapOf(
                                "status" to "NOT_FOUND", "customer_id" to "",
                                "debt_after_minor" to "", "message" to "草稿单不存在"
                            )
                            is CheckoutSaleResult.Conflict -> return mapOf(
                                "status" to "CONFLICT", "customer_id" to "",
                                "debt_after_minor" to "", "message" to "结账冲突，请重试"
                            )
                            is CheckoutSaleResult.EmptyOrder -> return mapOf(
                                "status" to "INVALID_ARGUMENT", "customer_id" to "",
                                "debt_after_minor" to "", "message" to "草稿单是空的"
                            )
                            is CheckoutSaleResult.InsufficientStock -> return mapOf(
                                "status" to "INVALID_ARGUMENT", "customer_id" to "",
                                "debt_after_minor" to "", "message" to "库存不足，结不了账"
                            )
                        }
                    }
                    draftTotal
                }

                when (val result = credit(
                    CustomerDebtRequest(
                        storeId = session.storeId,
                        customerId = customer.id,
                        amount = Money(fen),
                        idempotencyKey = "credit:${customer.id}:$fen",
                        note = "赊账销售"
                    )
                )) {
                    is CustomerDebtResult.Success -> mapOf(
                        "status" to "OK",
                        "customer_id" to customer.id,
                        "debt_after_minor" to result.balanceAfterMinor.toString(),
                        "message" to "已记账：「${customer.name}」赊账 $fen 分，共欠 ${result.balanceAfterMinor} 分"
                    )

                    is CustomerDebtResult.AlreadyCompleted -> mapOf(
                        "status" to "OK",
                        "customer_id" to customer.id,
                        "debt_after_minor" to result.balanceAfterMinor.toString(),
                        "message" to "这笔已经记过了，共欠 ${result.balanceAfterMinor} 分"
                    )

                    CustomerDebtResult.CustomerNotFound -> notFound(customerQuery)
                }
            }
        }
    }

    private fun notFound(query: String) = mapOf(
        "status" to "NOT_FOUND", "customer_id" to "", "debt_after_minor" to "",
        "message" to "没找到「$query」这个客户，先建一个？"
    )

    private fun ambiguous(
        query: String,
        resolution: Resolution.Ambiguous<com.smallshoping.app.domain.customer.Customer>,
        entities: Map<String, String>
    ) = mapOf(
        "status" to "AMBIGUOUS", "customer_id" to "", "debt_after_minor" to "",
        "message" to "有好几个像「$query」的客户：" +
            resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
        "ambiguous_key" to "customer",
        "ambiguous_tool" to "record_customer_credit",
        "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
        "intent_entities" to encodeEntities(entities)
    )
}

/**
 * settle_customer_debt：老张还100（write，MEDIUM，一律确认）。
 * 还款冲减欠款，不删除历史赊账流水（spec 04 §3 追加式）。
 */
class SettleCustomerDebtHandler(
    private val customerResolver: CustomerResolver,
    private val payment: ReceiveCustomerPaymentUseCase,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val customerQuery = entities.getValue("customer")
        val fen = parseAmount(entities["amount"]) ?: return mapOf(
            "status" to "INVALID_ARGUMENT", "customer_id" to "", "debt_after_minor" to "",
            "message" to "金额看不懂或不是正数（如：老张还100）"
        )
        return when (val resolution = customerResolver.resolve(customerQuery)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "customer_id" to "", "debt_after_minor" to "",
                "message" to "没找到「$customerQuery」这个客户"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "customer_id" to "", "debt_after_minor" to "",
                "message" to "有好几个像「$customerQuery」的客户：" +
                    resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
                "ambiguous_key" to "customer",
                "ambiguous_tool" to "settle_customer_debt",
                "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved -> {
                val customer = resolution.value
                when (val result = payment(
                    CustomerDebtRequest(
                        storeId = session.storeId,
                        customerId = customer.id,
                        amount = Money(fen),
                        idempotencyKey = "settle:${customer.id}:$fen",
                        note = "收款"
                    )
                )) {
                    is CustomerDebtResult.Success -> mapOf(
                        "status" to "OK",
                        "customer_id" to customer.id,
                        "debt_after_minor" to result.balanceAfterMinor.toString(),
                        "message" to "已收款：「${customer.name}」还 $fen 分，还欠 ${result.balanceAfterMinor} 分"
                    )

                    is CustomerDebtResult.AlreadyCompleted -> mapOf(
                        "status" to "OK",
                        "customer_id" to customer.id,
                        "debt_after_minor" to result.balanceAfterMinor.toString(),
                        "message" to "这笔收款已经记过了，还欠 ${result.balanceAfterMinor} 分"
                    )

                    CustomerDebtResult.CustomerNotFound -> mapOf(
                        "status" to "NOT_FOUND", "customer_id" to "", "debt_after_minor" to "",
                        "message" to "客户不存在"
                    )
                }
            }
        }
    }
}
