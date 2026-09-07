package com.smallshoping.app.ai.risk

import com.smallshoping.app.ai.orchestrator.Intent
import com.smallshoping.app.ai.orchestrator.IntentType
import com.smallshoping.app.ai.tools.ToolContract

/** 风险决策（spec 08 §2/§9）。 */
sealed interface RiskDecision {
    /** 低风险且意图明确、参数完整 → 自动执行 */
    data object AutoExecute : RiskDecision

    /** 需要老板确认或补充信息（携带原因/问题） */
    data class NeedConfirm(val reason: String) : RiskDecision

    /** 拒绝执行（如超出 V1 允许范围的操作） */
    data class Reject(val reason: String) : RiskDecision
}

/**
 * 风险门：V1 保守策略——
 * 仅 LOW 风险 + 无追问 + NONE 确认策略自动执行；
 * MEDIUM/HIGH 或带追问一律进入确认（spec 08：低风险自动、中风险确认、高风险强制确认）。
 *
 * Task 059 真机验收：确认提示改为老板能看懂的一句话
 * （如「把「土豆」的售价改成 4 元？请确认」），不再暴露工具名与内部枚举。
 */
class RiskGate {

    fun decide(contract: ToolContract, intent: Intent): RiskDecision {
        if (intent.clarification != null) {
            return RiskDecision.NeedConfirm("需要你补充：${intent.clarification}")
        }
        if (contract.riskLevel != RiskLevel.LOW) {
            return RiskDecision.NeedConfirm(friendlyQuestion(intent))
        }
        return RiskDecision.AutoExecute
    }

    /** 一句话确认（纯整数运算，金额分→元展示）。 */
    private fun friendlyQuestion(intent: Intent): String {
        val e = intent.entities
        val moneyFen = (e["price"] ?: e["amount"])?.toLongOrNull()
        val moneyText = moneyFen?.let { fenToYuan(it) }
        return when (intent.type) {
            IntentType.CHANGE_PRICE ->
                if (e["price_type"] == "cost") {
                    "把「${e["product"]}」的进价改成 ${moneyText}？请确认"
                } else {
                    "把「${e["product"]}」的售价改成 ${moneyText}？请确认"
                }

            IntentType.RECHARGE_MEMBER ->
                "给「${e["member"]}」充值 ${moneyText}？请确认"

            IntentType.RECORD_LOSS ->
                "报损「${e["product"]}」${e["quantity"]}？请确认"

            IntentType.CHECKOUT_SALE ->
                "这个单子要结账了？请确认"

            IntentType.REMOVE_SALE_ITEM ->
                "拿掉刚才加的那个？请确认"

            IntentType.RECORD_CUSTOMER_CREDIT ->
                if (e.containsKey("amount")) {
                    "记「${e["customer"]}」赊账 ${moneyText}？请确认"
                } else {
                    "「${e["customer"]}」这个单子先记账？请确认"
                }

            IntentType.SETTLE_CUSTOMER_DEBT ->
                "收「${e["customer"]}」还款 ${moneyText}？请确认"

            IntentType.CREATE_PRODUCT ->
                "新建商品「${e["name"]}」？请确认"

            IntentType.APPLY_YESTERDAY_PRICE ->
                "用昨天的价格？请确认"

            IntentType.REFUND_SALE ->
                "退款？请确认"

            else -> "这个操作需要老板确认后执行"
        }
    }

    private fun fenToYuan(fen: Long): String =
        if (fen % 100L == 0L) {
            "${fen / 100L} 元"
        } else {
            "${fen / 100L}.${(fen % 100L).toString().padStart(2, '0')} 元"
        }
}
