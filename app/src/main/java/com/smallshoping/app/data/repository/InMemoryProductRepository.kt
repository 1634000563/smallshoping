package com.smallshoping.app.data.repository

import com.smallshoping.app.domain.catalog.PriceHistoryEntry
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import com.smallshoping.app.domain.catalog.ProductRepository

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
}
