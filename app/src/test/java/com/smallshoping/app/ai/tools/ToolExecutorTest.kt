package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.context.SessionContext
import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentType
import com.smallshoping.app.ai.risk.ConfirmationGate
import com.smallshoping.app.ai.risk.RiskGate
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.data.repository.InMemoryProductRepository
import com.smallshoping.app.data.repository.InMemorySessionContextStore
import com.smallshoping.app.domain.catalog.AliasSource
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductAlias
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutorTest {

    private val products = InMemoryProductRepository()
    private val contexts = InMemorySessionContextStore()

    private val executor = ToolExecutor(
        catalog = V1ToolCatalog,
        riskGate = RiskGate(),
        confirmationGate = ConfirmationGate(),
        handlers = mapOf(
            ToolRef("find_product") to FindProductHandler(
                com.smallshoping.app.ai.entityresolution.ProductResolver(products)
            ),
            ToolRef("get_context") to GetContextHandler(contexts, "DEVICE-1"),
            ToolRef("create_product") to CreateProductHandler(products, "STORE-1")
        )
    )

    private val potato = Product(
        id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
        saleUnit = Unit.JIN, purchaseUnit = Unit.JIN,
        currentSalePrice = Money(380), currentCostPrice = Money(280)
    )

    @Test
    fun `正常路径：find_product 返回事实字段`() {
        products.saveProduct(potato)
        products.addAlias(ProductAlias("A-1", "P-1", "洋芋", normalize("洋芋"), AliasSource.BOSS_SPEECH, 90))
        val byName = executor.execute(
            Intent(IntentType.FIND_PRODUCT, entities = mapOf("query" to "土豆"))
        )
        assertTrue(byName is ToolResult.Success)
        assertEquals("OK", (byName as ToolResult.Success).data["status"])
        val byAlias = executor.execute(
            Intent(IntentType.FIND_PRODUCT, entities = mapOf("query" to "洋芋"))
        )
        assertTrue(byAlias is ToolResult.Success)
        assertEquals("OK", (byAlias as ToolResult.Success).data["status"])
    }

    @Test
    fun `正常路径：create_product 写入并可按 id 查询`() {
        val result = executor.execute(
            Intent(IntentType.CREATE_PRODUCT, entities = mapOf("name" to "螺丝"), requestId = "R-1")
        )
        assertTrue(result is ToolResult.NeedsConfirmation) // MEDIUM 需确认
        val pending = result as ToolResult.NeedsConfirmation
        val executed = executor.confirm(pending.requestId, approved = true)
        assertTrue(executed is ToolResult.Success)
        val id = (executed as ToolResult.Success).data["product_id"]!!
        assertTrue(products.findProductById(id) != null)
    }

    @Test
    fun `风险门：MEDIUM 工具未确认不执行`() {
        val result = executor.execute(
            Intent(IntentType.CHANGE_PRICE, entities = mapOf("product" to "土豆", "price" to "400"))
        )
        assertTrue(result is ToolResult.NeedsConfirmation)
    }

    @Test
    fun `Schema 校验失败：拒绝执行并带错误码`() {
        val result = executor.execute(
            Intent(IntentType.ADD_SALE_ITEM, entities = mapOf("product" to "土豆"))
        )
        assertTrue(result is ToolResult.Failure)
        assertEquals("SCHEMA_INVALID", (result as ToolResult.Failure).errorCode)
    }

    @Test
    fun `无处理器：返回 NO_HANDLER 而非假成功`() {
        val result = executor.execute(Intent(IntentType.GET_TODAY_SALES))
        assertTrue(result is ToolResult.Failure)
        assertEquals("NO_HANDLER", (result as ToolResult.Failure).errorCode)
    }

    @Test
    fun `确认只能针对最近请求：旧 requestId 无效`() {
        val r1 = executor.execute(
            Intent(IntentType.CHECKOUT_SALE, entities = mapOf("payment_method" to "cash"), requestId = "OLD")
        )
        assertTrue(r1 is ToolResult.NeedsConfirmation)
        val r2 = executor.execute(
            Intent(IntentType.RECHARGE_MEMBER, entities = mapOf("member" to "张姐", "amount" to "20000"), requestId = "NEW")
        )
        assertTrue(r2 is ToolResult.NeedsConfirmation)
        val wrong = executor.confirm("OLD", approved = true)
        assertTrue(wrong is ToolResult.Failure)
        assertEquals("CONFIRMATION_INVALID", (wrong as ToolResult.Failure).errorCode)
        val ok = executor.confirm("NEW", approved = false)
        assertTrue(ok is ToolResult.Rejected)
    }

    @Test
    fun `处理器异常与缺字段：都不得当作成功`() {
        val badExecutor = ToolExecutor(
            catalog = V1ToolCatalog,
            riskGate = RiskGate(),
            confirmationGate = ConfirmationGate(),
            handlers = mapOf(
                ToolRef("get_today_sales") to ToolHandler { throw IllegalStateException("boom") },
                ToolRef("get_context") to ToolHandler { mapOf("status" to "OK") } // 缺 context 字段
            )
        )
        val boom = badExecutor.execute(Intent(IntentType.GET_TODAY_SALES))
        assertTrue(boom is ToolResult.Failure)
        assertEquals("TOOL_ERROR", (boom as ToolResult.Failure).errorCode)
        val missingField = badExecutor.execute(Intent(IntentType.GET_CONTEXT))
        assertTrue(missingField is ToolResult.Failure)
        assertEquals("INTERNAL_ERROR", (missingField as ToolResult.Failure).errorCode)
    }

    @Test
    fun `get_context：读到会话引用`() {
        contexts.save(
            SessionContext(
                deviceSessionId = "DEVICE-1",
                lastProductId = "P-1",
                lastIntent = "sale_item",
                expiresAtMillis = System.currentTimeMillis() + 60_000L
            )
        )
        val result = executor.execute(Intent(IntentType.GET_CONTEXT))
        assertTrue(result is ToolResult.Success)
        assertEquals("OK", (result as ToolResult.Success).data["status"])
    }
}
