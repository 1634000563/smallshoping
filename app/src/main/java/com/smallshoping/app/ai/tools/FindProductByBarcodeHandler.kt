package com.smallshoping.app.ai.tools

import com.smallshoping.app.domain.catalog.ProductRepository

/**
 * find_product_by_barcode：扫码输入（read，LOW，无确认）。
 *
 * 条码是确定性输入（spec 10：扫码是通用输入方式），本地规则
 * 识别 8-14 位纯数字串 → 本 Handler 精确查商品；未录条码明确提示。
 */
class FindProductByBarcodeHandler(private val products: ProductRepository) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val barcode = entities.getValue("barcode")
        val product = products.findByBarcode(barcode)
            ?: return mapOf(
                "status" to "NOT_FOUND", "product" to "",
                "message" to "这个条码还没录，先建个商品把它扫进去？"
            )
        return mapOf(
            "status" to "OK",
            "product" to "${product.id}=${product.name}@${product.saleUnit.name}/${product.currentSalePrice.minor}",
            "message" to "「${product.name}」单价 ${product.currentSalePrice.minor} 分/${product.saleUnit.name}"
        )
    }
}
