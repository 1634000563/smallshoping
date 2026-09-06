package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.entityresolution.MemberResolver
import com.smallshoping.app.ai.entityresolution.Resolution
import com.smallshoping.app.ai.orchestrator.StoreSession
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.money.MoneyParser
import com.smallshoping.app.domain.member.MemberFundsQuery
import com.smallshoping.app.domain.member.RechargeMemberRequest
import com.smallshoping.app.domain.member.RechargeMemberResult
import com.smallshoping.app.domain.member.RechargeMemberUseCase

/**
 * find_member：按名称/别名/电话查找会员（read，LOW，无确认）。
 * 多候选不猜测，返回 AMBIGUOUS 由上层消歧（产品宪法 #9）。
 */
class FindMemberHandler(private val resolver: MemberResolver) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("query")
        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf("status" to "NOT_FOUND", "members" to "")

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS",
                "members" to resolution.candidates.joinToString("|") {
                    "${it.value.id}=${it.value.name}@${it.value.phone ?: ""}"
                }
            )

            is Resolution.Resolved -> {
                val m = resolution.value
                mapOf("status" to "OK", "members" to "${m.id}=${m.name}@${m.phone ?: ""}")
            }
        }
    }
}

/**
 * get_member_balance：查会员余额（read，LOW，无确认）。
 * 余额来自 member_ledger 派生值（spec 04 §6），本 Handler 不持余额字段。
 */
class GetMemberBalanceHandler(
    private val resolver: MemberResolver,
    private val funds: MemberFundsQuery
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("member")
        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "balance_minor" to "",
                "message" to "没找到「$query」这个会员"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "balance_minor" to "",
                "message" to "有好几个像「$query」的会员：" +
                    resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？"
            )

            is Resolution.Resolved -> {
                val m = resolution.value
                val balance = funds.balanceOf(m.id) ?: 0L
                mapOf(
                    "status" to "OK",
                    "balance_minor" to balance.toString(),
                    "message" to "「${m.name}」余额 ${balance} 分"
                )
            }
        }
    }
}

/**
 * recharge_member：给会员充值（write，MEDIUM，一律确认）。
 *
 * - 金额必须为正（分）；商品歧义不猜测；
 * - 幂等键按（会员+金额）确定性生成，AI 重试不重复入账；
 * - 确认流由 RiskGate/ConfirmationGate 在 ToolExecutor 层处理，
 *   本 Handler 只做参数校验与 Domain 调用。
 */
class RechargeMemberHandler(
    private val resolver: MemberResolver,
    private val recharge: RechargeMemberUseCase,
    private val session: StoreSession
) : ToolHandler {

    override fun execute(entities: Map<String, String>): Map<String, String> {
        val query = entities.getValue("member")
        val rawAmount = entities.getValue("amount")
        // 本地 parser 已换算为分；云端模型可能传「200」或「2块8」，两种都接受
        val fen = rawAmount.toLongOrNull() ?: MoneyParser.parseYuanToMinor(rawAmount) ?: return mapOf(
            "status" to "INVALID_ARGUMENT", "member_id" to "", "balance_after_minor" to "",
            "message" to "金额看不懂（如：给张姐充200）"
        )
        if (fen <= 0) return mapOf(
            "status" to "INVALID_ARGUMENT", "member_id" to "", "balance_after_minor" to "",
            "message" to "充值金额必须大于 0"
        )

        return when (val resolution = resolver.resolve(query)) {
            is Resolution.NotFound -> mapOf(
                "status" to "NOT_FOUND", "member_id" to "", "balance_after_minor" to "",
                "message" to "没找到「$query」这个会员，先建一个？"
            )

            is Resolution.Ambiguous -> mapOf(
                "status" to "AMBIGUOUS", "member_id" to "", "balance_after_minor" to "",
                "message" to "有好几个像「$query」的会员：" +
                    resolution.candidates.joinToString("、") { it.value.name } + "，是哪一个？"
            )

            is Resolution.Resolved -> {
                val member = resolution.value
                when (val result = recharge(
                    RechargeMemberRequest(
                        storeId = session.storeId,
                        memberId = member.id,
                        amount = Money(fen),
                        idempotencyKey = "recharge:${member.id}:$fen"
                    )
                )) {
                    is RechargeMemberResult.Success -> mapOf(
                        "status" to "OK",
                        "member_id" to member.id,
                        "balance_after_minor" to result.balanceAfterMinor.toString(),
                        "message" to "已充值：${member.name} ${fen} 分，余额 ${result.balanceAfterMinor} 分"
                    )

                    is RechargeMemberResult.AlreadyCompleted -> mapOf(
                        "status" to "OK",
                        "member_id" to member.id,
                        "balance_after_minor" to result.balanceAfterMinor.toString(),
                        "message" to "这笔已经充过了，余额 ${result.balanceAfterMinor} 分"
                    )

                    RechargeMemberResult.MemberNotFound -> mapOf(
                        "status" to "NOT_FOUND", "member_id" to "", "balance_after_minor" to "",
                        "message" to "会员不存在"
                    )
                }
            }
        }
    }
}
