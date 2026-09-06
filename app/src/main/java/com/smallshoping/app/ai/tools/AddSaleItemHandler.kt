package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContext
import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.core.quantity.QuantityParser
import com.smallshoping.app.domain.sales.AddSaleItemRequest
import com.smallshoping.app.domain.sales.AddSaleItemResult
import com.smallshoping.app.domain.sales.AddSaleItemUseCase

/**
 * add_sale_item：卖两斤土豆 → 解析商品与数量 → Domain 加项。
 *
 * - 商品歧义（多个候选）不猜测，返回 AMBIGUOUS 由上层追问；
 * - 金额由 Domain 按目录价计算，AI 不报金额；
 * - 成功后把草稿单写入会话上下文（spec 06：当前订单）。
 */
class AddSaleItemHandler(
    private val resolver: ProductResolver,
    private val addItem: AddSaleItemUseCase,
    private val contexts: SessionContextStore,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("product")
        val parsedQuantity = QuantityParser.parse(entities.getValue("quantity"))
            ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "item_id" to "",
                "message" to "数量看不懂（如：两斤土豆、2.36斤、3个螺丝）"
            )
        val quantity = parsedQuantity.quantity

        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "item_id" to "",
                "message" to "没找到「$query」这个商品，先建一个？"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "item_id" to "",
                "message" to "有好几个像「$query」的商品：" +
                    resolution.candidates.joinToString("、") { it.value.name } +
                    "，是哪一个？",
                "ambiguous_key" to "product",
                "ambiguous_tool" to "add_sale_item",
                "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved -> {
                val context = contexts.load(session.deviceId)
                val request = AddSaleItemRequest(
                    storeId = session.storeId,
                    draftSaleId = context?.activeSaleOrderId,
                    productId = resolution.value.id,
                    quantity = quantity
                )
                when (val result = addItem(request)) {
                    is AddSaleItemResult.Success -> {
                        // 上下文增强：记住当前草稿单与最近商品（spec 06 §1）
                        val updated = context?.copy(
                            activeSaleOrderId = result.sale.id,
                            lastProductId = resolution.value.id
                        )
                            ?: SessionContext(
                                deviceSessionId = session.deviceId,
                                activeSaleOrderId = result.sale.id,
                                lastProductId = resolution.value.id,
                                expiresAtMillis = System.currentTimeMillis() + 30 * 60 * 1000L
                            )
                        contexts.save(updated)
                        val item = result.sale.items.last()
                        mapOf(
                            "status" to "OK",
                            "item_id" to item.id,
                            "sale_id" to result.sale.id,
                            "message" to "已加入：${item.productName} ${parsedQuantity.displayText}，小计 ${item.subtotal.minor} 分"
                        )
                    }

                    is AddSaleItemResult.UnitMismatch -> mapOf(
                        "status" to "INVALID_ARGUMENT", "item_id" to "",
                        "message" to "数量单位要用「${resolution.value.saleUnit.name}」"
                    )

                    AddSaleItemResult.ProductNotFound -> mapOf(
                        "status" to "NOT_FOUND", "item_id" to "", "message" to "商品不存在"
                    )
                }
            }
        }
    }
}
