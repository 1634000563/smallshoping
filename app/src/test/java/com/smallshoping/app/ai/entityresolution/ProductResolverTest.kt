package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.domain.catalog.AliasSource
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
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
}
