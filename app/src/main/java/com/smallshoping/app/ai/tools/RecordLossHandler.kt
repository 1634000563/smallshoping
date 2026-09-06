package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.core.quantity.QuantityParser
import com.smallshoping.app.domain.inventory.RecordLossRequest
import com.smallshoping.app.domain.inventory.RecordLossResult
import com.smallshoping.app.domain.inventory.RecordLossUseCase

/**
 * record_loss：损耗两斤土豆（write，MEDIUM，一律确认）。
 *
 * - 商品歧义不猜测；数量必须可解析为基本单位刻度；
 * - 幂等键按（商品+数量）确定性生成，AI 重试不重复报损；
 * - 损耗成本快照由 Domain 计算（当前加权平均成本 × 数量），AI 不报价。
 */
class RecordLossHandler(
    private val resolver: ProductResolver,
    private val recordLoss: RecordLossUseCase
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("product")
        val parsedQuantity = QuantityParser.parse(entities.getValue("quantity"))
            ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "loss_id" to "",
                "message" to "数量看不懂（如：损耗两斤土豆）"
            )

        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "loss_id" to "",
                "message" to "没找到「$query」这个商品，先建一个？"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "loss_id" to "",
                "message" to "有好几个像「$query」的商品：" +
                    resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
                "ambiguous_key" to "product",
                "ambiguous_tool" to "record_loss",
                "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved -> {
                val product = resolution.value
                when (val result = recordLoss(
                    RecordLossRequest(
                        productId = product.id,
                        quantity = parsedQuantity.quantity,
                        reason = "语音报损",
                        idempotencyKey = "loss:${product.id}:${parsedQuantity.quantity.scaled}"
                    )
                )) {
                    is RecordLossResult.Success -> mapOf(
                        "status" to "OK",
                        "loss_id" to result.loss.id,
                        "message" to "已记录损耗：${product.name} ${parsedQuantity.displayText}，" +
                            "剩余库存 ${result.stockAfter}，损耗成本 ${result.loss.costAmountMinor} 分"
                    )

                    is RecordLossResult.AlreadyCompleted -> mapOf(
                        "status" to "OK",
                        "loss_id" to result.loss.id,
                        "message" to "这笔损耗已经记过了"
                    )

                    is RecordLossResult.InsufficientStock -> mapOf(
                        "status" to "INVALID_ARGUMENT", "loss_id" to "",
                        "message" to "库存只有 ${result.stock}，损耗要 ${result.requested}，超了"
                    )

                    is RecordLossResult.UnitMismatch -> mapOf(
                        "status" to "INVALID_ARGUMENT", "loss_id" to "",
                        "message" to "数量单位要用「${result.expected.name}」"
                    )

                    RecordLossResult.ProductNotFound -> mapOf(
                        "status" to "NOT_FOUND", "loss_id" to "", "message" to "商品不存在"
                    )

                    RecordLossResult.Conflict -> mapOf(
                        "status" to "CONFLICT", "loss_id" to "",
                        "message" to "重复提交冲突，请重试"
                    )
                }
            }
        }
    }
}
