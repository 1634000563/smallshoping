package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.entityresolution.ProductResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.money.MoneyParser
import com.smallshoping.app.domain.catalog.ChangePriceRequest
import com.smallshoping.app.domain.catalog.ChangePriceResult
import com.smallshoping.app.domain.catalog.ChangeProductPriceUseCase

/**
 * change_price：土豆改价三块五 / 土豆的进价改成两块五
 * （write，MEDIUM，确认策略 LARGE_DELTA，V1 RiskGate 保守策略一律确认）。
 *
 * - 默认改售价；price_type=cost 时改进货价（Task 059）；
 * - 商品歧义不猜测；价格必须可解析为正分；
 * - 改价事实 = 当前价更新 + 价格历史追加（spec 02 只追加不覆盖）。
 */
class ChangePriceHandler(
    private val resolver: ProductResolver,
    private val changePrice: ChangeProductPriceUseCase,
    private val changeCost: com.smallshoping.app.domain.catalog.ChangeCostPriceUseCase
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("product")
        val isCost = entities["price_type"] == "cost"
        val fen = entities.getValue("price").toLongOrNull()
            ?: MoneyParser.parseYuanToMinor(entities.getValue("price"))
            ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "product_id" to "",
                "old_price_minor" to "", "new_price_minor" to "",
                "message" to "价格看不懂（如：土豆改价3块5）"
            )
        if (fen <= 0) return mapOf(
            "status" to "INVALID_ARGUMENT", "product_id" to "",
            "old_price_minor" to "", "new_price_minor" to "",
            "message" to "价格必须是正数"
        )

        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "product_id" to "",
                "old_price_minor" to "", "new_price_minor" to "",
                "message" to "没找到「$query」这个商品，先建一个？"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "product_id" to "",
                "old_price_minor" to "", "new_price_minor" to "",
                "message" to "有好几个像「$query」的商品：" +
                    resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？",
                "ambiguous_key" to "product",
                "ambiguous_tool" to "change_price",
                "candidates" to resolution.candidates.joinToString("|") { "${it.value.id}=${it.value.name}" },
                "intent_entities" to encodeEntities(entities)
            )

            is Resolution.Resolved -> {
                val product = resolution.value
                if (isCost) {
                    // 改进货价（Task 059）
                    when (val result = changeCost(
                        com.smallshoping.app.domain.catalog.ChangeCostRequest(
                            product.id, Money(fen), source = "boss_speech"
                        )
                    )) {
                        is com.smallshoping.app.domain.catalog.ChangeCostResult.Success -> mapOf(
                            "status" to "OK",
                            "product_id" to product.id,
                            "old_price_minor" to (result.oldCostMinor ?: 0L).toString(),
                            "new_price_minor" to result.newCostMinor.toString(),
                            "message" to "「${product.name}」进价改为 ${result.newCostMinor} 分" +
                                "（下次进货将按此价计算毛利基准）"
                        )

                        is com.smallshoping.app.domain.catalog.ChangeCostResult.Unchanged -> mapOf(
                            "status" to "OK",
                            "product_id" to product.id,
                            "old_price_minor" to (result.product.currentCostPrice?.minor ?: 0L).toString(),
                            "new_price_minor" to (result.product.currentCostPrice?.minor ?: 0L).toString(),
                            "message" to "「${product.name}」进价本来就是 " +
                                "${result.product.currentCostPrice?.minor} 分，没动"
                        )

                        com.smallshoping.app.domain.catalog.ChangeCostResult.ProductNotFound -> mapOf(
                            "status" to "NOT_FOUND", "product_id" to "",
                            "old_price_minor" to "", "new_price_minor" to "",
                            "message" to "商品不存在"
                        )
                    }
                } else {
                    when (val result = changePrice(
                        ChangePriceRequest(product.id, Money(fen), source = "boss_speech")
                    )) {
                        is ChangePriceResult.Success -> {
                            // 大额变化警示（spec 08 §8：土豆从4块改成40块应触发异常提示）
                            val warning = if (
                                result.oldPriceMinor > 0 &&
                                (result.newPriceMinor >= result.oldPriceMinor * 10 ||
                                    result.oldPriceMinor >= result.newPriceMinor * 10)
                            ) {
                                "⚠️ 价格变化很大，请留意："
                            } else {
                                ""
                            }
                            mapOf(
                                "status" to "OK",
                                "product_id" to product.id,
                                "old_price_minor" to result.oldPriceMinor.toString(),
                                "new_price_minor" to result.newPriceMinor.toString(),
                                "message" to "$warning「${product.name}」改价：" +
                                    "${result.oldPriceMinor} 分 → ${result.newPriceMinor} 分"
                            )
                        }

                        is ChangePriceResult.Unchanged -> mapOf(
                            "status" to "OK",
                            "product_id" to product.id,
                            "old_price_minor" to result.product.currentSalePrice.minor.toString(),
                            "new_price_minor" to result.product.currentSalePrice.minor.toString(),
                            "message" to "「${product.name}」本来就是 ${result.product.currentSalePrice.minor} 分，没动"
                        )

                        ChangePriceResult.ProductNotFound -> mapOf(
                            "status" to "NOT_FOUND", "product_id" to "",
                            "old_price_minor" to "", "new_price_minor" to "",
                            "message" to "商品不存在"
                        )
                    }
                }
            }
        }
    }
}
