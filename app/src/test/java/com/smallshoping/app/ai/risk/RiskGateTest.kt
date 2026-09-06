package com.smallshoping.app.ai.risk

import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentType
import com.smallshoping.app.ai.tools.ToolRef
import com.smallshoping.app.ai.tools.V1ToolCatalog
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskGateTest {

    private val gate = RiskGate()

    @Test
    fun `LOW 风险且无追问：自动执行`() {
        val contract = V1ToolCatalog.tool(ToolRef("find_product"))!!
        val intent = Intent(IntentType.FIND_PRODUCT, entities = mapOf("query" to "土豆"))
        assertTrue(gate.decide(contract, intent) is RiskDecision.AutoExecute)
    }

    @Test
    fun `带追问的意图：必须确认，即使 LOW`() {
        val contract = V1ToolCatalog.tool(ToolRef("get_stock"))!!
        val intent = Intent(IntentType.GET_STOCK, clarification = "要查哪个商品？")
        assertTrue(gate.decide(contract, intent) is RiskDecision.NeedConfirm)
    }

    @Test
    fun `MEDIUM 风险：一律确认`() {
        val contract = V1ToolCatalog.tool(ToolRef("change_price"))!!
        val intent = Intent(
            IntentType.CHANGE_PRICE,
            entities = mapOf("product" to "土豆", "price" to "400")
        )
        assertTrue(gate.decide(contract, intent) is RiskDecision.NeedConfirm)
    }

    @Test
    fun `HIGH 风险：强制确认`() {
        val contract = V1ToolCatalog.tool(ToolRef("adjust_stock"))!!
        val intent = Intent(
            IntentType.ADJUST_STOCK,
            entities = mapOf("product" to "土豆", "quantity" to "10斤")
        )
        assertTrue(gate.decide(contract, intent) is RiskDecision.NeedConfirm)
    }

    @Test
    fun `外部支付确认策略：checkout 需确认`() {
        val contract = V1ToolCatalog.tool(ToolRef("checkout_sale"))!!
        val intent = Intent(IntentType.CHECKOUT_SALE, entities = mapOf("payment_method" to "cash"))
        assertTrue(gate.decide(contract, intent) is RiskDecision.NeedConfirm)
    }
}
