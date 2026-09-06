package com.smallshoping.app.domain.sales

/** 移除草稿行请求：从草稿单中移除该商品最近加入的一行。 */
data class RemoveSaleItemRequest(
    val draftSaleId: String,
    val productId: String
)

sealed interface RemoveSaleItemResult {
    data class Success(val sale: SaleOrder, val removedItemId: String) : RemoveSaleItemResult
    data object DraftNotFound : RemoveSaleItemResult
    data object ProductNotInDraft : RemoveSaleItemResult
}

/**
 * 移除草稿行（spec 08 §1：LOW 加购物车范畴；Tool 层按 MEDIUM 确认）。
 *
 * 只允许改 DRAFT 单（完成单是不可再改的历史事实，追加式）；
 * 同商品多行时移除最近加入的一行（「刚才那个不要了」语义）。
 */
class RemoveSaleItemUseCase(private val sales: SaleRepository) {

    operator fun invoke(request: RemoveSaleItemRequest): RemoveSaleItemResult {
        val draft = sales.findDraft(request.draftSaleId)
            ?: return RemoveSaleItemResult.DraftNotFound
        if (draft.status != SaleStatus.DRAFT) {
            return RemoveSaleItemResult.DraftNotFound
        }
        val target = draft.items.lastOrNull { it.productId == request.productId }
            ?: return RemoveSaleItemResult.ProductNotInDraft
        val updated = draft.copy(
            items = draft.items.filterNot { it.id == target.id },
            total = draft.computeTotal() - target.subtotal
        )
        sales.saveDraft(updated)
        return RemoveSaleItemResult.Success(updated, target.id)
    }
}
