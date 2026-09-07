package com.smallshoping.app.ai.eval

import com.smallshoping.app.ai.orchestrator.IntentType
import com.smallshoping.app.ai.tools.ToolRef
import com.smallshoping.app.ai.tools.V1ToolCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Task 054 发布门禁：黄金语句集数据质量守护（spec 15 §3/§4）。
 * 守护的是「评测数据本身可信」：规模、id 唯一、工具真实存在、
 * intent↔tool 映射一致、五行业覆盖、核心语句覆盖。
 */
class GoldenSetQualityTest {

    private val cases = EvalRunner.loadCases(
        File(System.getProperty("user.dir"), "../docs/evals/golden_cases.jsonl")
    )

    @Test
    fun `规模与 id：至少 20 条且 id 全局唯一`() {
        assertTrue("黄金集规模不足：${cases.size}", cases.size >= 20)
        val duplicates = cases.groupBy { it.id }.filterValues { it.size > 1 }.keys
        assertEquals("黄金集 id 重复", emptySet<String>(), duplicates)
    }

    @Test
    fun `工具真实存在且 intent 与 tool 映射一致`() {
        for (case in cases) {
            assertTrue(
                "${case.id} 的工具 ${case.expectedTool} 不在目录",
                V1ToolCatalog.tool(ToolRef(case.expectedTool)) != null
            )
            val intentName = IntentType.fromTool(case.expectedTool)?.name
            assertEquals(
                "${case.id} expected_intent 与 expected_tool 不一致",
                case.expectedIntent, intentName
            )
        }
    }

    @Test
    fun `五行业覆盖（spec 15 第3节）：菜、水果、便利店、五金、水电材料`() {
        val prefixes = cases.map { it.id.substringBefore('-') }.toSet()
        for (required in listOf("veg", "fruit", "conv", "plumb")) {
            assertTrue("缺少行业前缀 $required：$prefixes", required in prefixes)
        }
        assertTrue(
            "缺少五金行业（hardware/hw）", "hardware" in prefixes || "hw" in prefixes
        )
    }

    @Test
    fun `核心语句覆盖（spec 15 第4节）`() {
        val corpus = cases.joinToString("\n") { it.utterance }
        val required = listOf(
            "卖两斤土豆", "土豆两斤六", "刚才那个不要了", "充200", "还有多少钱",
            "进100斤白菜", "上次那些", "M8", "先记账"
        )
        for (keyword in required) {
            assertTrue("黄金集缺少核心语句关键词「$keyword」", corpus.contains(keyword))
        }
        // 昨天价：任意「昨天…价」句式
        assertTrue(corpus.contains("昨天") && corpus.contains("价"))
    }

    @Test
    fun `评测风险分布：MEDIUM 与 LOW 语句都存在，且存在期望澄清语句`() {
        val risks = cases.map { it.expectedRisk }.toSet()
        assertTrue("缺少 MEDIUM 风险语句（危险路径未被评测）", "MEDIUM" in risks)
        assertTrue("缺少 LOW 风险语句", "LOW" in risks)
        assertTrue("缺少期望澄清语句（clarification: true）", cases.any { it.expectedClarification })
    }
}
