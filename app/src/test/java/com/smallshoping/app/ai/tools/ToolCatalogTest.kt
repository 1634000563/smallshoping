package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.providers.AiVersions
import com.smallshoping.app.ai.risk.RiskLevel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 053 升级：本测试直接读取 `docs/schemas/tool-catalog.json`（机器可读契约，
 * spec 07 §6），与运行时目录 V1ToolCatalog 全量比对——新增/修改 Tool 两处必须同步，
 * 否则测试失败。
 */
class ToolCatalogTest {

    private val jsonRoot: JSONObject = JSONObject(
        File(System.getProperty("user.dir"), "../docs/schemas/tool-catalog.json").readText(Charsets.UTF_8)
    )
    private val jsonTools = jsonRoot.getJSONArray("tools")

    private fun jsonTool(name: String): JSONObject =
        (0 until jsonTools.length()).map { jsonTools.getJSONObject(it) }
            .first { it.getString("name") == name }

    private fun contract(name: String): ToolContract =
        V1ToolCatalog.tool(ToolRef(name)) ?: error("目录缺少工具 $name")

    @Test
    fun `目录与 tool-catalog json 工具名全集一致`() {
        val jsonNames = (0 until jsonTools.length())
            .map { jsonTools.getJSONObject(it).getString("name") }.toSet()
        assertEquals(jsonNames, V1ToolCatalog.all().map { it.ref.name }.toSet())
    }

    @Test
    fun `schema 版本：json schema_version 与代码单一事实源一致`() {
        assertEquals(AiVersions.TOOL_SCHEMA_VERSION, jsonRoot.getString("schema_version"))
    }

    @Test
    fun `全量比对：risk 等级与代码目录一致`() {
        val riskMap = mapOf(
            "LOW" to RiskLevel.LOW, "MEDIUM" to RiskLevel.MEDIUM, "HIGH" to RiskLevel.HIGH
        )
        for (i in 0 until jsonTools.length()) {
            val tool = jsonTools.getJSONObject(i)
            val name = tool.getString("name")
            assertEquals(
                "$name risk 不一致", riskMap.getValue(tool.getString("risk")),
                contract(name).riskLevel
            )
        }
    }

    @Test
    fun `全量比对：mode 与幂等要求一致（write 一律幂等）`() {
        for (i in 0 until jsonTools.length()) {
            val tool = jsonTools.getJSONObject(i)
            val name = tool.getString("name")
            val contract = contract(name)
            if (tool.getString("mode") == "read") {
                assertFalse("$name 只读工具不应要求幂等键", contract.idempotencyRequired)
            } else {
                assertTrue("$name 写工具必须要求幂等键", contract.idempotencyRequired)
                assertTrue("$name 写工具必须在 json 声明 idempotent", tool.optBoolean("idempotent"))
            }
        }
    }

    @Test
    fun `全量比对：confirmation 策略与代码目录一致`() {
        val policyMap = mapOf(
            "none" to ConfirmationPolicy.NONE,
            "when_ambiguous" to ConfirmationPolicy.WHEN_AMBIGUOUS,
            "required" to ConfirmationPolicy.REQUIRED,
            "payment_confirmation" to ConfirmationPolicy.PAYMENT_CONFIRMATION,
            "large_delta" to ConfirmationPolicy.LARGE_DELTA
        )
        for (i in 0 until jsonTools.length()) {
            val tool = jsonTools.getJSONObject(i)
            val name = tool.getString("name")
            val expected = if (tool.has("confirmation")) {
                policyMap.getValue(tool.getString("confirmation"))
            } else {
                ConfirmationPolicy.NONE
            }
            assertEquals("$name confirmation 不一致", expected, contract(name).confirmationPolicy)
        }
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
