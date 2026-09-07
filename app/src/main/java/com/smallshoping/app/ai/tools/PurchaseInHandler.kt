package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.money.MoneyParser
import com.smallshoping.app.core.quantity.ParsedQuantity
import com.smallshoping.app.core.quantity.QuantityParser
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.purchase.PurchaseInRequest
import com.smallshoping.app.domain.purchase.PurchaseInResult
import com.smallshoping.app.domain.purchase.PurchaseInUseCase
import java.util.UUID

/**
 * purchase_in：进100斤土豆，成本2块8 → 入库 + 加权平均成本。
 *
 * - 商品不存在时**顺手建档再入库**（ADR-018）：「进100斤土豆」本身就是
 *   明确意图，反问「先建一个？」是多余的步骤；建档单位取数量单位
 *   （斤/公斤/克/个/盒/米），售价记 0 元待老板说改价；
 * - 商品歧义不猜测；进价解析失败明确报错（绝不静默丢成本）；
 * - 幂等键按（商品+数量+成本）确定性生成，AI 重试不重复入库。
 */
class PurchaseInHandler(
    private val resolver: ProductResolver,
    private val products: ProductRepository,
    private val purchaseIn: PurchaseInUseCase,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("product")
        val parsedQuantity = QuantityParser.parse(entities.getValue("quantity"))
            ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "purchase_id" to "",
                "message" to "数量看不懂（如：进100斤土豆）"
            )
        // 成本可选，但给了就必须能解析（不静默丢弃）
        val cost = entities["cost"]?.takeIf { it.isNotBlank() }?.let { raw ->
            MoneyParser.parseYuanToMinor(raw)?.let { Money(it) }
                ?: return mapOf(
                    "status" to "INVALID_ARGUMENT", "purchase_id" to "",
                    "message" to "进价「$raw」看不懂（如：成本2块8）"
                )
        }

        return when (val resolution = resolver.resolve(query)) {
            // ADR-018：商品不存在 → 顺手建档（单位取数量单位，售价 0 待改价）再入库
            is Resolution.NotFound -> {
                val unit = parsedQuantity.sourceUnit ?: Unit.PIECE
                val product = Product(
                    id = UUID.randomUUID().toString(),
                    storeId = session.storeId,
                    name = query,
                    normalizedName = normalize(query),
                    saleUnit = unit,
                    purchaseUnit = unit,
                    currentSalePrice = Money(0),
                    currentCostPrice = cost
                )
                products.saveProduct(product)
                val result = executePurchase(product, parsedQuantity, cost, entities)
                result + ("message" to buildString {
                    append("刚建了商品「${product.name}」")
                    append("，${result["message"] ?: ""}")
                    if (result["status"] == "OK" && cost == null) {
                        append("；还没定价，说「${product.name}改成X块」来定价")
                    }
                })
            }

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "purchase_id" to "",
                "message" to "有好几个像「$query」的商品：" +
                    resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
                "ambiguous_key" to "product",
                "ambiguous_tool" to "purchase_in",
                "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved ->
                executePurchase(resolution.value, parsedQuantity, cost, entities)
        }
    }

    /** 执行入库并返回结果 map（新建与既有商品共用同一路径）。 */
    private fun executePurchase(
        product: Product,
        parsedQuantity: ParsedQuantity,
        cost: Money?,
        entities: Map<String, String>
    ): Map<String, String> {
        val idempotencyKey = "purchase:${product.id}:${parsedQuantity.quantity.scaled}:${cost?.minor ?: "nocost"}"
        return when (val result = purchaseIn(
            PurchaseInRequest(
                storeId = session.storeId,
                productId = product.id,
                quantity = parsedQuantity.quantity,
                unitCost = cost,
                idempotencyKey = idempotencyKey
            )
        )) {
            is PurchaseInResult.Success -> mapOf(
                "status" to "OK",
                "purchase_id" to result.order.id,
                "message" to buildString {
                    append("已入库：${product.name} ${parsedQuantity.displayText}")
                    result.newCostMinor?.let { append("，成本 ${it} 分/单位") }
                    entities["note"]?.takeIf { it.isNotBlank() }?.let { append("（$it）") }
                }
            )

            is PurchaseInResult.AlreadyCompleted -> mapOf(
                "status" to "OK",
                "purchase_id" to result.order.id,
                "message" to "这批货已经入过库了"
            )

            is PurchaseInResult.UnitMismatch -> mapOf(
                "status" to "INVALID_ARGUMENT", "purchase_id" to "",
                "message" to "数量单位要用「${product.purchaseUnit.name}」"
            )

            PurchaseInResult.ProductNotFound -> mapOf(
                "status" to "NOT_FOUND", "purchase_id" to "", "message" to "商品不存在"
            )

            PurchaseInResult.Conflict -> mapOf(
                "status" to "CONFLICT", "purchase_id" to "",
                "message" to "重复提交冲突，请重试"
            )
        }
    }
}
