package com.smallshoping.app.ai.orchestrator

import com.smallshoping.app.ai.context.CandidateRef
import com.smallshoping.app.ai.context.DisambiguationStore
import com.smallshoping.app.ai.context.PendingQuestion
import com.smallshoping.app.ai.providers.AiProvider
import com.smallshoping.app.ai.providers.AiResponse
import com.smallshoping.app.ai.providers.GatewayRequest
import com.smallshoping.app.ai.tools.ToolExecutor
import com.smallshoping.app.ai.tools.ToolResult

/** 编排层给界面/老板的回复。 */
sealed interface OrchestratorReply {

    data class Text(val text: String) : OrchestratorReply

    data class Question(val question: String) : OrchestratorReply

    data class NeedsConfirm(val requestId: String, val question: String) : OrchestratorReply
}

/**
 * AI 编排器（spec 05 §2 Agent Loop 的客户端侧实现）：
 * Provider 产出（文本/工具调用/澄清）→ Tool 执行链（Schema→Risk→Domain）。
 *
 * - 模型只能选择请求中授权的工具（最小权限）；
 * - AI 失败不改变任何事实，只降级为文本提示（离线宪法 #6）；
 * - 需要确认的操作挂起，由 [confirm] 在老板确认后继续执行；
 * - 实体歧义（spec 08 §8）挂起追问——无论歧义发生在首次执行还是确认后执行，
 *   老板用候选名/「第X个」回答即重跑原意图；发起新任务则旧追问作废。
 */
class AiOrchestrator(
    private val provider: AiProvider,
    private val executor: ToolExecutor,
    private val disambiguation: DisambiguationStore
) {

    /** 待确认请求 → 发起设备：confirm 后若出现歧义，追问要挂回原设备。 */
    private val confirmDevices = HashMap<String, String>()

    fun handle(request: GatewayRequest): OrchestratorReply {
        // 1) 追问优先：老板的回答先看是不是在消歧（候选名/第X个）
        disambiguation.pending(request.deviceId)?.let { pending ->
            pending.choose(request.inputText)?.let { chosen ->
                disambiguation.clear(request.deviceId)
                val reply = executeToolCall(
                    pending.toolName,
                    pending.entities + (pending.ambiguousKey to chosen.name),
                    request.deviceId
                )
                return recordConfirmDevice(reply, request.deviceId)
            }
        }

        // 2) 口语确认/拒绝（spec 08 §9）：是/确定 确认最近请求；不/取消 拒绝
        val normalized = com.smallshoping.app.core.common.normalize(request.inputText)
        if (normalized in CONFIRM_WORDS) {
            executor.pendingConfirmationId()?.let { return confirm(it, approved = true) }
        }
        if (normalized in REJECT_WORDS) {
            executor.pendingConfirmationId()?.let { return confirm(it, approved = false) }
        }

        // 3) 常规路径
        val response = provider.complete(request)
        return when (response) {
            is AiResponse.FinalText -> OrchestratorReply.Text(response.text)

            is AiResponse.Clarification -> OrchestratorReply.Question(response.question)

            AiResponse.ConfirmationRequired ->
                OrchestratorReply.Question("这个操作需要你确认后我才会执行")

            is AiResponse.ModelError -> OrchestratorReply.Text(
                "AI 服务暂时不可用：${response.message}。账务未受任何影响，可用键盘/扫码继续营业。"
            )

            is AiResponse.RateLimited -> OrchestratorReply.Text("请求太频繁，稍等一下再说")

            is AiResponse.ToolCall -> {
                if (response.toolName !in request.allowedTools) {
                    OrchestratorReply.Question("这个操作暂时没有开放")
                } else {
                    // 新任务取代旧追问
                    disambiguation.clear(request.deviceId)
                    recordConfirmDevice(
                        executeToolCall(response.toolName, response.entities, request.deviceId),
                        request.deviceId
                    )
                }
            }
        }
    }

    /** 老板对挂起请求的确认/拒绝（spec 08 §9）。 */
    fun confirm(requestId: String, approved: Boolean): OrchestratorReply {
        val result = executor.confirm(requestId, approved)
        // 确认后执行若出现实体歧义，同样挂起追问（挂回发起设备）
        confirmDevices.remove(requestId)?.let { proposeIfAmbiguity(result, it) }
        return toReply(result)
    }

    private fun recordConfirmDevice(reply: OrchestratorReply, deviceId: String): OrchestratorReply {
        if (reply is OrchestratorReply.NeedsConfirm) {
            confirmDevices[reply.requestId] = deviceId
        }
        return reply
    }

    private fun executeToolCall(
        toolName: String,
        entities: Map<String, String>,
        deviceId: String
    ): OrchestratorReply {
        val type = IntentType.fromTool(toolName)
            ?: return OrchestratorReply.Question("不认识这个操作")
        val result = executor.execute(Intent(type = type, entities = entities))
        proposeIfAmbiguity(result, deviceId)
        return toReply(result)
    }

    /** 实体歧义 → 挂起追问，等待下一轮回答（候选数据由 Handler 以 AMBIGUOUS 状态返回）。 */
    private fun proposeIfAmbiguity(result: ToolResult, deviceId: String) {
        if (result !is ToolResult.Success || result.data["status"] != "AMBIGUOUS") return
        val toolName = result.data["ambiguous_tool"] ?: return
        parseCandidates(result.data["candidates"])?.takeIf { it.isNotEmpty() }?.let { candidates ->
            disambiguation.propose(
                deviceId,
                PendingQuestion(
                    toolName = toolName,
                    entities = result.data["intent_entities"]?.let { decodeEntities(it) } ?: emptyMap(),
                    ambiguousKey = result.data["ambiguous_key"] ?: "",
                    candidates = candidates,
                    expiresAtMillis = System.currentTimeMillis() + QUESTION_TTL_MILLIS
                )
            )
        }
    }

    /** 解析「id=name|id=name」候选列表。 */
    private fun parseCandidates(raw: String?): List<CandidateRef>? {
        if (raw.isNullOrBlank()) return null
        return raw.split("|").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else CandidateRef(part.substring(0, idx), part.substring(idx + 1))
        }.ifEmpty { null }
    }

    /** 解析「key=value&key=value」意图参数编码。 */
    private fun decodeEntities(raw: String): Map<String, String> =
        raw.split("&").mapNotNull { part ->
            val idx = part.indexOf('=')
            if (idx <= 0) null else part.substring(0, idx) to part.substring(idx + 1)
        }.toMap()

    private fun toReply(result: ToolResult): OrchestratorReply = when (result) {
        is ToolResult.Success ->
            OrchestratorReply.Text(result.data["message"] ?: result.data.toString())

        is ToolResult.Failure ->
            OrchestratorReply.Text("${result.errorCode}：${result.message}")

        is ToolResult.NeedsConfirmation ->
            OrchestratorReply.NeedsConfirm(result.requestId, result.question)

        is ToolResult.Rejected -> OrchestratorReply.Text("已取消")
    }

    private companion object {
        /** 追问有效期：2 分钟不回答自动作废。 */
        const val QUESTION_TTL_MILLIS = 2L * 60 * 1000

        /** 口语确认词（spec 08 §9：是/确定/执行）。 */
        val CONFIRM_WORDS = setOf(
            "是", "是的", "对", "对的", "确定", "好的", "好", "确认", "执行", "嗯", "行", "可以"
        )

        /** 口语拒绝词。 */
        val REJECT_WORDS = setOf("不", "不用", "不要", "取消", "算了", "别", "不行", "不要了")
    }
}
