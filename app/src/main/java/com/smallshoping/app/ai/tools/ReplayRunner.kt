package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentType
import com.smallshoping.app.domain.journal.CommandJournal

/**
 * 崩溃恢复重放器（Task 045）：
 * 按命令日志顺序重放写命令——入参相同 → Handler 确定性生成
 * 相同幂等键 → 账本/仓库幂等去重（AlreadyCompleted 视为重放成功），
 * 不重复记账；个别命令失败不中断其余命令，收集进结果。
 */
class ReplayRunner(
    private val journal: CommandJournal,
    private val executor: ToolExecutor
) {

    data class ReplayResult(
        val replayed: Int,
        val failed: List<Pair<String, String>> // toolName → 失败原因
    ) {
        val allSucceeded: Boolean get() = failed.isEmpty()
    }

    fun replayAll(): ReplayResult {
        val failures = ArrayList<Pair<String, String>>()
        var replayed = 0
        for (record in journal.all()) {
            val type = IntentType.fromTool(record.toolName)
            if (type == null) {
                failures.add(record.toolName to "未注册的 Tool")
                continue
            }
            val outcome = executor.replay(
                Intent(type = type, entities = decodeEntities(record.entitiesJson))
            )
            when (outcome) {
                is ToolResult.Success -> replayed++
                is ToolResult.Failure ->
                    failures.add(record.toolName to "${outcome.errorCode}：${outcome.message}")
                is ToolResult.NeedsConfirmation ->
                    failures.add(record.toolName to "重放不应进入确认流")
                is ToolResult.Rejected ->
                    failures.add(record.toolName to "重放被拒绝")
            }
        }
        return ReplayResult(replayed, failures)
    }
}
