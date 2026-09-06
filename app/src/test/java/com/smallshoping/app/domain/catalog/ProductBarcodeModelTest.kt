package com.smallshoping.app.domain.catalog

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 036：商品条码模型与查询（一个商品多条码，扫码确定性输入）。 */
class ProductBarcodeModelTest {

    private val products = InMemoryProductRepository()

    private fun seedProduct() {
        products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
                currentSalePrice = Money(380), currentCostPrice = null
            )
        )
    }

    @Test
    fun `模型校验：空条码与空商品拒绝`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProductBarcode(productId = "P-1", barcode = "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProductBarcode(productId = "", barcode = "6901234567890")
        }
    }

    @Test
    fun `多条码：按条码精确查商品，主条码标记`() {
        seedProduct()
        products.addBarcode(
            ProductBarcode(productId = "P-1", barcode = "6901234567890", barcodeType = BarcodeType.EAN13, isPrimary = true)
        )
        products.addBarcode(
            ProductBarcode(productId = "P-1", barcode = "POTATO-01", barcodeType = BarcodeType.CODE128)
        )
        assertEquals("P-1", products.findByBarcode("6901234567890")?.id)
        assertEquals("P-1", products.findByBarcode("POTATO-01")?.id)
        assertNull(products.findByBarcode("6909999999999"))
        assertEquals(2, products.barcodes("P-1").size)
        assertTrue(products.barcodes("P-1").any { it.isPrimary })
        // 条码指向不存在商品拒绝
        assertThrows(IllegalArgumentException::class.java) {
            products.addBarcode(ProductBarcode(productId = "P-404", barcode = "6900000000000"))
        }
    }
}
