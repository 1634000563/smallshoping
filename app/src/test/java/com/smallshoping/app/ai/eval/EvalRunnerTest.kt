package com.smallshoping.app.ai.eval

import com.smallshoping.app.ai.providers.LocalRuleParser
import com.smallshoping.app.ai.tools.V1ToolCatalog
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Task 038 验收：黄金语句集全量回归与评分阈值（spec 15 §5/§6）。 */
class EvalRunnerTest {

    private val runner = EvalRunner(LocalRuleParser())
    private val allowedTools = V1ToolCatalog.all().map { it.ref.name }

    @Test
    fun `黄金语句集全量通过：正确率达标且无危险解析`() {
        val cases = EvalRunner.loadCases(
            File(System.getProperty("user.dir"), "../docs/evals/golden_cases.jsonl")
        )
        assertTrue("黄金语句集不能为空", cases.size >= 10)
        val report = runner.run(cases, allowedTools)
        assertTrue(
            "黄金集未通过（${report.accuracy}）：\n" +
                report.failures.joinToString("\n") { "  ${it.case.id} ${it.case.utterance} → ${it.detail}" },
            report.pass
        )
    }

    @Test
    fun `评分阈值：95% 达标通过，危险解析一票否决`() {
        val mostlyOk = (1..19).map {
            GoldenCase("c$it", "卖两斤土豆", "add_sale_item", "LOW")
        } + GoldenCase("c20", "刚才那个不要了", "remove_sale_item", "MEDIUM")
        val ok = runner.run(mostlyOk, allowedTools)
        assertTrue(ok.accuracy >= EvalRunner.PASS_THRESHOLD)
        assertTrue(ok.pass)

        // 危险解析：语句被期望为 MEDIUM 风险却解析成 LOW 工具 → 整体不通过
        val dangerous = listOf(
            GoldenCase("d1", "卖两斤土豆", "add_sale_item", "LOW"),
            GoldenCase("d2", "卖两斤土豆", "add_sale_item", "MEDIUM")
        )
        val bad = runner.run(dangerous, allowedTools)
        assertTrue(!bad.pass)
        assertTrue(bad.failures.any { it.detail.contains(EvalRunner.DANGEROUS) })
    }
}
