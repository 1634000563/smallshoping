package com.smallshoping.app.domain.catalog

import java.util.UUID

/** 条码类型（spec 03 product_barcode.barcode_type 最小子集）。 */
enum class BarcodeType { EAN13, CODE128, OTHER }

/**
 * 商品条码（spec 03 product_barcode 表）：
 * 一个商品可有多条码（spec 02 §2「不假设一个商品=一个条码」），
 * 其中至多一条为主条码。
 */
data class ProductBarcode(
    val id: String = UUID.randomUUID().toString(),
    val productId: String,
    val barcode: String,
    val barcodeType: BarcodeType = BarcodeType.OTHER,
    val isPrimary: Boolean = false
) {

    init {
        require(productId.isNotBlank()) { "productId 不能为空" }
        require(barcode.isNotBlank()) { "条码不能为空" }
    }
}
