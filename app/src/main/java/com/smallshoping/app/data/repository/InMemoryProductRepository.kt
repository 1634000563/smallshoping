package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import com.smallshoping.app.domain.catalog.ProductAttribute
import com.smallshoping.app.domain.catalog.ProductBarcode
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.catalog.UnitConversion
import com.smallshoping.app.core.quantity.Unit

/**
 * 内存商品目录实现：线程安全。
 *
 * 语义基线同 [com.smallshoping.app.data.ledger.InMemoryLedger]：
 * 真实持久化实现（Room/SQLite）须通过同一组测试。
 */
class InMemoryProductRepository : ProductRepository {

    private val lock = Any()
    private val byId = LinkedHashMap<String, Product>()
    private val byName = HashMap<String, Product>()
    private val byAlias = HashMap<String, Product>()
    private val aliasList = LinkedHashMap<String, MutableList<ProductAlias>>()
    private val priceHistoryList = LinkedHashMap<String, MutableList<PriceHistoryEntry>>()
    private val attributeList = LinkedHashMap<String, MutableList<ProductAttribute>>()
    private val attributeIndex = HashMap<String, ProductAttribute>() // productId|normalizedName
    private val conversionList = LinkedHashMap<String, MutableList<UnitConversion>>()
    private val conversionIndex = HashMap<String, UnitConversion>() // productId|fromCode|toCode
    private val barcodeList = LinkedHashMap<String, MutableList<ProductBarcode>>()
    private val barcodeIndex = HashMap<String, Product>() // barcode → 商品

    override fun saveProduct(product: Product) = synchronized(lock) {
        byId[product.id] = product
        byName[product.normalizedName] = product
    }

    override fun findProductById(id: String): Product? = synchronized(lock) {
        byId[id]
    }

    override fun allProducts(): List<Product> = synchronized(lock) {
        byId.values.toList()
    }

    override fun findByNormalizedName(normalizedName: String): Product? = synchronized(lock) {
        byName[normalizedName]
    }

    override fun findByAlias(normalizedAlias: String): Product? = synchronized(lock) {
        byAlias[normalizedAlias]
    }

    override fun addAlias(alias: ProductAlias) {
        synchronized(lock) {
            require(byId.containsKey(alias.productId)) { "别名指向的商品不存在：${alias.productId}" }
            byAlias[alias.normalizedAlias] = byId.getValue(alias.productId)
            aliasList.getOrPut(alias.productId) { ArrayList() }.add(alias)
        }
    }

    override fun aliases(productId: String): List<ProductAlias> = synchronized(lock) {
        aliasList[productId]?.toList() ?: emptyList()
    }

    override fun priceHistory(productId: String): List<PriceHistoryEntry> = synchronized(lock) {
        priceHistoryList[productId]?.toList() ?: emptyList()
    }

    override fun applyPriceChange(
        productId: String,
        newPrice: com.smallshoping.app.core.money.Money,
        history: PriceHistoryEntry
    ): Product? = synchronized(lock) {
        val product = byId[productId] ?: return null
        val updated = product.copy(currentSalePrice = newPrice)
        byId[productId] = updated
        byName[updated.normalizedName] = updated
        priceHistoryList.getOrPut(productId) { ArrayList() }.add(history)
        updated
    }

    /** 追加价格历史（只追加，不修改）。 */
    fun appendPriceHistory(entry: PriceHistoryEntry) = synchronized(lock) {
        require(byId.containsKey(entry.productId)) { "价格历史指向的商品不存在：${entry.productId}" }
        priceHistoryList.getOrPut(entry.productId) { ArrayList() }.add(entry)
    }

    override fun addAttribute(attribute: ProductAttribute) = synchronized(lock) {
        require(byId.containsKey(attribute.productId)) { "属性指向的商品不存在：${attribute.productId}" }
        attributeList.getOrPut(attribute.productId) { ArrayList() }.add(attribute)
        attributeIndex["${attribute.productId}|${attribute.normalizedName}"] = attribute
    }

    override fun attributes(productId: String): List<ProductAttribute> = synchronized(lock) {
        attributeList[productId]?.toList() ?: emptyList()
    }

    override fun findAttribute(productId: String, normalizedName: String): ProductAttribute? =
        synchronized(lock) {
            attributeIndex["$productId|$normalizedName"]
        }

    override fun addConversion(conversion: UnitConversion) = synchronized(lock) {
        require(byId.containsKey(conversion.productId)) { "换算指向的商品不存在：${conversion.productId}" }
        conversionList.getOrPut(conversion.productId) { ArrayList() }.add(conversion)
        conversionIndex[
            "${conversion.productId}|${conversion.fromUnit.code}|${conversion.toUnit.code}"
        ] = conversion
    }

    override fun conversions(productId: String): List<UnitConversion> = synchronized(lock) {
        conversionList[productId]?.toList() ?: emptyList()
    }

    override fun findConversion(productId: String, fromUnit: Unit, toUnit: Unit): UnitConversion? =
        synchronized(lock) {
            conversionIndex["$productId|${fromUnit.code}|${toUnit.code}"]
        }

    override fun addBarcode(barcode: ProductBarcode) = synchronized(lock) {
        require(byId.containsKey(barcode.productId)) { "条码指向的商品不存在：${barcode.productId}" }
        barcodeList.getOrPut(barcode.productId) { ArrayList() }.add(barcode)
        barcodeIndex[barcode.barcode] = byId.getValue(barcode.productId)
    }

    override fun findByBarcode(barcode: String): Product? = synchronized(lock) {
        barcodeIndex[barcode]
    }

    override fun barcodes(productId: String): List<ProductBarcode> = synchronized(lock) {
        barcodeList[productId]?.toList() ?: emptyList()
    }
}
