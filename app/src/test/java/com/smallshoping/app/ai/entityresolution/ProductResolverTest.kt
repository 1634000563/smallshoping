package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.domain.catalog.AliasSource
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import com.smallshoping.app.domain.catalog.ProductAttribute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductResolverTest {

    private val repo = InMemoryProductRepository()
    private val resolver = ProductResolver(repo)

    private fun product(id: String, name: String) = Product(
        id = id, storeId = "STORE-1", name = name, normalizedName = normalize(name),
        saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
        currentSalePrice = Money(100), currentCostPrice = null
    )

    @Test
    fun `精确名命中：直接解析唯一实体`() {
        repo.saveProduct(product("P-1", "土豆"))
        val r = resolver.resolve(" 土豆 ")
        assertTrue(r is Resolution.Resolved)
        assertEquals("P-1", (r as Resolution.Resolved).value.id)
        assertEquals(100, r.score)
    }

    @Test
    fun `别名命中：按别名置信度计分`() {
        repo.saveProduct(product("P-1", "马铃薯"))
        repo.addAlias(ProductAlias("A-1", "P-1", "土豆", normalize("土豆"), AliasSource.BOSS_SPEECH, 90))
        val r = resolver.resolve("土豆")
        assertTrue(r is Resolution.Resolved)
        assertEquals("P-1", (r as Resolution.Resolved).value.id)
        assertEquals(90, r.score)
        assertEquals("alias_exact", r.reason)
    }

    @Test
    fun `无匹配：返回 NotFound 不猜测`() {
        repo.saveProduct(product("P-1", "土豆"))
        assertTrue(resolver.resolve("洗衣机") is Resolution.NotFound)
        assertTrue(resolver.resolve("   ") is Resolution.NotFound)
    }

    @Test
    fun `前缀候选唯一时解析成功`() {
        repo.saveProduct(product("P-1", "土豆片"))
        val r = resolver.resolve("土豆")
        assertTrue(r is Resolution.Resolved)
        assertEquals("P-1", (r as Resolution.Resolved).value.id)
        assertEquals(60, r.score)
    }

    @Test
    fun `多候选：返回 Ambiguous 并给出排序候选，不替老板选`() {
        repo.saveProduct(product("P-1", "土豆"))
        repo.saveProduct(product("P-2", "土豆片"))
        val r = resolver.resolve("土")
        assertTrue(r is Resolution.Ambiguous)
        val candidates = (r as Resolution.Ambiguous).candidates
        assertEquals(2, candidates.size)
        // 名称前缀命中排前
        assertEquals("P-1", candidates[0].value.id)
        assertEquals("name_prefix", candidates[0].reason)
    }

    @Test
    fun `别名包含命中可作为兜底候选`() {
        repo.saveProduct(product("P-1", "西红柿"))
        repo.addAlias(ProductAlias("A-1", "P-1", "小番茄", normalize("小番茄"), AliasSource.MANUAL, 80))
        val r = resolver.resolve("番茄")
        assertTrue(r is Resolution.Resolved)
        assertEquals("P-1", (r as Resolution.Resolved).value.id)
        assertEquals(20, r.score)
    }

    // ---- Task 032：五金规格属性匹配（跨行业通用）----

    private fun seedBolt(attributes: List<Pair<String, String>>) {
        val bolt = product("P-10", "304 M8x30 外六角螺栓")
        repo.saveProduct(bolt)
        attributes.forEach { (name, value) ->
            repo.addAttribute(
                ProductAttribute(
                    productId = "P-10", name = name, normalizedName = normalize(name),
                    value = value, normalizedValue = normalize(value)
                )
            )
        }
    }

    @Test
    fun `规格 token 属性精确命中：304 的螺栓`() {
        seedBolt(listOf("材质" to "304", "规格" to "M8x30", "类型" to "外六角螺栓"))
        val r = resolver.resolve("304 的螺栓")
        assertTrue(r is Resolution.Resolved)
        val resolved = r as Resolution.Resolved
        assertEquals("P-10", resolved.value.id)
        assertEquals(90, resolved.score)
        assertEquals("attr_value_exact", resolved.reason)
    }

    @Test
    fun `规格 token 命中：M8x30 螺丝`() {
        seedBolt(listOf("材质" to "304", "规格" to "M8x30"))
        val r = resolver.resolve("M8x30 螺丝")
        assertTrue(r is Resolution.Resolved)
        assertEquals("P-10", (r as Resolution.Resolved).value.id)
    }

    @Test
    fun `多商品同规格：返回歧义不猜测`() {
        seedBolt(listOf("材质" to "304"))
        val nut = product("P-11", "六角螺母")
        repo.saveProduct(nut)
        repo.addAttribute(
            ProductAttribute(
                productId = "P-11", name = "材质", normalizedName = normalize("材质"),
                value = "304", normalizedValue = normalize("304")
            )
        )
        val r = resolver.resolve("304")
        assertTrue(r is Resolution.Ambiguous)
        assertEquals(2, (r as Resolution.Ambiguous).candidates.size)
    }
}
