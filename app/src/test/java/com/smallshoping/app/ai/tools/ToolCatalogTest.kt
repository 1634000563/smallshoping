package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.risk.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCatalogTest {

    /** 与 docs/schemas/tool-catalog.json v1.0 的工具名全集一致（守护两处同步）。 */
    private val jsonToolNames = setOf(
        "find_product", "find_product_by_barcode", "get_stock", "get_today_sales",
        "get_month_sales", "get_top_products", "get_low_stock", "find_member",
        "get_member_balance", "find_customer", "get_customer_debt", "create_product",
        "purchase_in", "create_sale", "checkout_sale", "refund_sale", "change_price",
        "recharge_member", "charge_member", "record_customer_credit", "settle_customer_debt",
        "record_loss", "adjust_stock", "add_sale_item", "remove_sale_item",
        "get_current_sale", "cancel_sale", "get_stock_history", "get_loss_report",
        "get_profit_summary", "create_fulfillment", "update_fulfillment_status",
        "get_fulfillment", "get_context"
    )

    @Test
    fun `目录与 tool-catalog json 工具名全集一致`() {
        val catalogNames = V1ToolCatalog.all().map { it.ref.name }.toSet()
        assertEquals(jsonToolNames, catalogNames)
    }

    @Test
    fun `版本化：全量引用为 name_v1，且可查回`() {
        for (contract in V1ToolCatalog.all()) {
            assertEquals("${contract.ref.name}.v1", contract.ref.fullName)
            assertEquals(contract, V1ToolCatalog.tool(ToolRef(contract.ref.name)))
        }
        assertNull(V1ToolCatalog.tool(ToolRef("no_such_tool")))
    }

    @Test
    fun `宪法一致：写工具一律要求幂等键，全部允许离线`() {
        for (contract in V1ToolCatalog.all()) {
            assertTrue("${contract.ref.fullName} 必须允许离线", contract.allowedOffline)
            assertTrue("${contract.ref.fullName} 必须声明错误码", contract.errorCodes.isNotEmpty())
        }
        val readTools = V1ToolCatalog.all().filter { it.riskLevel == RiskLevel.LOW && !it.idempotencyRequired }
        assertTrue(readTools.isNotEmpty())
        // 写工具（要求幂等键）均有审计动作
        for (contract in V1ToolCatalog.all().filter { it.idempotencyRequired }) {
            assertTrue(contract.auditAction.isNotBlank())
        }
    }

    @Test
    fun `关键风险与确认策略符合 spec 08`() {
        val checkout = V1ToolCatalog.tool(ToolRef("checkout_sale"))!!
        assertEquals(ConfirmationPolicy.PAYMENT_CONFIRMATION, checkout.confirmationPolicy)
        val adjust = V1ToolCatalog.tool(ToolRef("adjust_stock"))!!
        assertEquals(RiskLevel.HIGH, adjust.riskLevel)
        assertEquals(ConfirmationPolicy.REQUIRED, adjust.confirmationPolicy)
        val changePrice = V1ToolCatalog.tool(ToolRef("change_price"))!!
        assertEquals(ConfirmationPolicy.LARGE_DELTA, changePrice.confirmationPolicy)
        val refund = V1ToolCatalog.tool(ToolRef("refund_sale"))!!
        assertEquals(ConfirmationPolicy.REQUIRED, refund.confirmationPolicy)
    }

    @Test
    fun `成功结果必须返回事实字段（spec 07 第4节）`() {
        for (contract in V1ToolCatalog.all()) {
            assertTrue(
                "${contract.ref.fullName} 缺少 status 字段",
                contract.successResultFields.contains("status")
            )
        }
    }

    @Test
    fun `契约构造自校验：非法引用被拒绝`() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ToolRef("Bad-Name")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            ToolRef("ok_name", majorVersion = 0)
        }
    }
}
