package com.smallshoping.app.domain.catalog

/**
 * 商品目录端口（Domain 侧契约）。
 *
 * 名称/别名查询一律使用归一化值；Data 层实现负责持久化，
 * AI 与 UI 不得直接持有本端口之外的数据访问能力。
 */
interface ProductRepository {

    fun saveProduct(product: Product)

    fun findProductById(id: String): Product?

    /** 按归一化商品名精确查找。 */
    fun findByNormalizedName(normalizedName: String): Product?

    /** 按归一化别名精确查找。 */
    fun findByAlias(normalizedAlias: String): Product?

    fun addAlias(alias: ProductAlias)

    fun aliases(productId: String): List<ProductAlias>

    /** 某商品全部价格历史（按时间追加顺序）。 */
    fun priceHistory(productId: String): List<PriceHistoryEntry>
}
