package com.smallshoping.app.ai.providers

import com.smallshoping.app.BuildConfig
import com.smallshoping.app.ai.eval.EvalRunner
import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentType
import com.smallshoping.app.ai.risk.ConfirmationGate
import com.smallshoping.app.ai.risk.RiskGate
import com.smallshoping.app.ai.tools.CreateProductHandler
import com.smallshoping.app.ai.tools.ToolExecutor
import com.smallshoping.app.ai.tools.ToolRef
import com.smallshoping.app.ai.tools.ToolResult
import com.smallshoping.app.ai.tools.V1ToolCatalog
import com.smallshoping.app.data.repository.InMemoryCommandJournal
import com.smallshoping.app.data.repository.InMemoryProductRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 053：Prompt / Tool Schema 版本化守护。
 * 代码版本事实源（[AiVersions]）与文档标记（system.md / tool-catalog.json）、
 * 请求默认值、命令日志四元组（spec 05 §9）、Eval 报告必须一致。
 */
class AiVersioningTest {

    @Test
    fun `system md 版本标记与代码事实源一致`() {
        val text = File(System.getProperty("user.dir"), "../docs/prompts/system.md")
            .readText(Charsets.UTF_8)
        val marker = Regex("<!--\\s*prompt-version:\\s*(\\S+)\\s*-->").find(text)?.groupValues?.get(1)
        assertEquals("system.md 缺少或版本标记不一致", AiVersions.PROMPT_VERSION, marker)
    }

    @Test
    fun `tool-catalog json schema_version 与代码事实源一致`() {
        val text = File(System.getProperty("user.dir"), "../docs/schemas/tool-catalog.json")
            .readText(Charsets.UTF_8)
        val version = Regex("\"schema_version\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        assertEquals("tool-catalog.json schema_version 不一致", AiVersions.TOOL_SCHEMA_VERSION, version)
    }

    @Test
    fun `GatewayRequest 默认携带当前版本（spec 05 第9节）`() {
        val request = GatewayRequest(
            storeId = "STORE-1", deviceId = "DEVICE-1", appVersion = "0.1.0",
            inputText = "卖两斤土豆", allowedTools = listOf("add_sale_item")
        )
        assertEquals(AiVersions.PROMPT_VERSION, request.promptVersion)
        assertEquals(AiVersions.TOOL_SCHEMA_VERSION, request.toolSchemaVersion)
    }

    @Test
    fun `写工具成功落日志时记录版本四元组（spec 05 第9节）`() {
        val journal = InMemoryCommandJournal()
        val executor = ToolExecutor(
            catalog = V1ToolCatalog,
            riskGate = RiskGate(),
            confirmationGate = ConfirmationGate(),
            handlers = mapOf(
                ToolRef("create_product") to CreateProductHandler(
                    InMemoryProductRepository(), "STORE-1"
                )
            ),
            journal = journal
        )
        val pending = executor.execute(
            Intent(IntentType.CREATE_PRODUCT, entities = mapOf("name" to "螺丝"), requestId = "R-1")
        )
        assertTrue(pending is ToolResult.NeedsConfirmation) // MEDIUM 需确认
        executor.confirm((pending as ToolResult.NeedsConfirmation).requestId, approved = true)

        val record = journal.all().single()
        assertEquals(AiVersions.MODEL_ID, record.modelId)
        assertEquals(AiVersions.PROMPT_VERSION, record.promptVersion)
        assertEquals(AiVersions.TOOL_SCHEMA_VERSION, record.toolSchemaVersion)
        assertEquals(BuildConfig.VERSION_NAME, record.appVersion)
    }

    @Test
    fun `Eval 报告携带 Prompt 与 Schema 版本（回归定位依据）`() {
        val runner = EvalRunner(LocalRuleParser())
        val cases = listOf(
            EvalRunner.loadCases(File(System.getProperty("user.dir"), "../docs/evals/golden_cases.jsonl"))
                .first()
        )
        val report = runner.run(cases, V1ToolCatalog.all().map { it.ref.name })
        assertEquals(AiVersions.PROMPT_VERSION, report.promptVersion)
        assertEquals(AiVersions.TOOL_SCHEMA_VERSION, report.toolSchemaVersion)
    }
}
