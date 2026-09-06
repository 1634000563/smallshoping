package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContextStore
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.domain.sales.RemoveSaleItemRequest
import com.smallshoping.app.domain.sales.RemoveSaleItemResult
import com.smallshoping.app.domain.sales.RemoveSaleItemUseCase

/**
 * remove_sale_item：刚才那个不要了（write，MEDIUM，确认后执行）。
 *
 * 商品省略时取会话上下文的最近商品（spec 06 §1）；只移除草稿单中
 * 该商品最近加入的一行，完成单不可改（追加式）。
 */
class RemoveSaleItemHandler(
    private val removeItem: RemoveSaleItemUseCase,
    private val contexts: SessionContextStore,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val draftId = contexts.load(session.deviceId)?.activeSaleOrderId
            ?: return mapOf(
                "status" to "NOT_FOUND", "item_id" to "",
                "message" to "现在没有正在卖的单子"
            )
        val productId = contexts.load(session.deviceId)?.lastProductId
            ?: return mapOf(
                "status" to "NOT_FOUND", "item_id" to "",
                "message" to "不记得刚才那个是什么，说下商品名？"
            )
        return when (val result = removeItem(
            RemoveSaleItemRequest(draftSaleId = draftId, productId = productId)
        )) {
            is RemoveSaleItemResult.Success -> mapOf(
                "status" to "OK",
                "item_id" to result.removedItemId,
                "message" to "已从单子上拿掉刚才那个"
            )

            RemoveSaleItemResult.DraftNotFound -> mapOf(
                "status" to "NOT_FOUND", "item_id" to "",
                "message" to "现在没有正在卖的单子"
            )

            RemoveSaleItemResult.ProductNotInDraft -> mapOf(
                "status" to "NOT_FOUND", "item_id" to "",
                "message" to "单子上没有这个商品"
            )
        }
    }
}
