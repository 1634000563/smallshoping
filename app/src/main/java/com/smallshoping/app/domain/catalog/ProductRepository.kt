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

    /** 全部商品（Entity Resolution 候选扫描用）。 */
    fun allProducts(): List<Product>

    /** 按归一化商品名精确查找。 */
    fun findByNormalizedName(normalizedName: String): Product?

    /** 按归一化别名精确查找。 */
    fun findByAlias(normalizedAlias: String): Product?

    fun addAlias(alias: ProductAlias)

    fun aliases(productId: String): List<ProductAlias>

    /** 某商品全部价格历史（按时间追加顺序）。 */
    fun priceHistory(productId: String): List<PriceHistoryEntry>

    /**
     * 原子改价：更新当前售价并追加价格历史（只追加不覆盖，spec 02）。
     * 商品不存在返回 null。
     */
    fun applyPriceChange(
        productId: String,
        newPrice: com.smallshoping.app.core.money.Money,
        history: PriceHistoryEntry
    ): Product?

    /** 追加商品属性（spec 03 product_attribute）。 */
    fun addAttribute(attribute: ProductAttribute)

    /** 某商品全部属性（按追加顺序）。 */
    fun attributes(productId: String): List<ProductAttribute>

    /** 按归一化属性名精确查找（五金规格查询用）。 */
    fun findAttribute(productId: String, normalizedName: String): ProductAttribute?

    /** 追加商品级包装换算（spec 03 unit_conversion）。 */
    fun addConversion(conversion: UnitConversion)

    /** 某商品全部包装换算。 */
    fun conversions(productId: String): List<UnitConversion>

    /** 按（商品+from+to）精确查找换算。 */
    fun findConversion(productId: String, fromUnit: com.smallshoping.app.core.quantity.Unit, toUnit: com.smallshoping.app.core.quantity.Unit): UnitConversion?
}
