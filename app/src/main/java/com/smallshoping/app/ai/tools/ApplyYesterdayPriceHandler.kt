package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.domain.catalog.ChangePriceRequest
import com.smallshoping.app.domain.catalog.ChangeProductPriceUseCase
import com.smallshoping.app.domain.catalog.ChangePriceResult
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.catalog.YesterdayPriceQuery

/**
 * apply_yesterday_price：还是昨天那个价格（spec 06 §6 解析顺序）。
 *
 * 1. 当前商品上下文（最近商品）；
 * 2. 昨日售价历史；
 * 3. 唯一有效价格 → 直接改价；多个 → 消歧（复用 Task 025 追问状态机，
 *    老板报价格或「第X个」后本 Handler 以 price 实体重入）；
 * 4. 无记录 → 明确提示。禁止猜测（产品宪法 #9）。
 */
class ApplyYesterdayPriceHandler(
    private val products: ProductRepository,
    private val contexts: SessionContextStore,
    private val session: StoreSession,
    private val yesterdayPrices: YesterdayPriceQuery,
    private val changePrice: ChangeProductPriceUseCase
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        // 1) 商品上下文
        val productId = contexts.load(session.deviceId)?.lastProductId
        if (productId == null) return mapOf(
            "status" to "NOT_FOUND", "product_id" to "", "new_price_minor" to "",
            "message" to "先告诉我要改哪个商品（如：卖两斤土豆）"
        )
        val product = products.findProductById(productId)
            ?: return mapOf(
                "status" to "NOT_FOUND", "product_id" to "", "new_price_minor" to "",
                "message" to "最近那个商品已经不存在了，请重新说一次"
            )

        // 2) 昨日售价（消歧后重入时直接用老板选中的 price）
        val history = yesterdayPrices.yesterdaySalePrices(productId)
        val distinctMinors = history.map { it.newPrice.minor }.distinct()
        val target = entities["price"]?.let { raw ->
            raw.toLongOrNull() ?: return mapOf(
                "status" to "INVALID_ARGUMENT", "product_id" to "", "new_price_minor" to "",
                "message" to "价格看不懂：$raw"
            )
        }

        return when {
            // 3) 多个价格 → 追问（重入时只校验所选价格在昨日集合内）
            target == null && distinctMinors.size > 1 -> mapOf(
                "status" to "AMBIGUOUS", "product_id" to "", "new_price_minor" to "",
                "message" to "昨天「${product.name}」有多个价格：" +
                    distinctMinors.joinToString("、") { "$it 分" } + "，用哪一个？",
                "ambiguous_key" to "price",
                "ambiguous_tool" to "apply_yesterday_price",
                "candidates" to distinctMinors.joinToString("|") { "$it=$it" },
                "intent_entities" to encodeEntities(entities)
            )

            target != null && target !in distinctMinors -> mapOf(
                "status" to "INVALID_ARGUMENT", "product_id" to "", "new_price_minor" to "",
                "message" to "$target 分不是昨天的价格，换个说法？"
            )

            distinctMinors.isEmpty() -> mapOf(
                "status" to "NOT_FOUND", "product_id" to "", "new_price_minor" to "",
                "message" to "「${product.name}」昨天没有价格记录"
            )

            // 4) 唯一价格（或消歧选中的价格）→ 改价
            else -> {
                val newPrice = Money(target ?: distinctMinors.single())
                when (val result = changePrice(
                    ChangePriceRequest(productId = product.id, newPrice = newPrice, source = "yesterday_price")
                )) {
                    is ChangePriceResult.Success -> mapOf(
                        "status" to "OK",
                        "product_id" to product.id,
                        "new_price_minor" to result.newPriceMinor.toString(),
                        "message" to "「${product.name}」已按昨天价格改价：" +
                            "${result.oldPriceMinor} 分 → ${result.newPriceMinor} 分"
                    )

                    is ChangePriceResult.Unchanged -> mapOf(
                        "status" to "OK",
                        "product_id" to product.id,
                        "new_price_minor" to result.product.currentSalePrice.minor.toString(),
                        "message" to "「${product.name}」本来就是昨天这个价格，没动"
                    )

                    ChangePriceResult.ProductNotFound -> mapOf(
                        "status" to "NOT_FOUND", "product_id" to "", "new_price_minor" to "",
                        "message" to "商品不存在"
                    )
                }
            }
        }
    }
}
