package com.smallshoping.app.ai.providers

import com.smallshoping.app.core.common.normalize

/**
 * 本地规则解析 Provider（spec 05 §5：简单命令优先本地 parser）。
 *
 * 确定性、零网络、零密钥；只覆盖少数高频句式，复杂表达交云端模型。
 * 解析失败返回 [AiResponse.Clarification]，绝不猜测（产品宪法 #9）。
 *
 * 支持的句式（V1 最小集，随 Task 014/025 扩展）：
 * - 「今天卖了多少钱」→ GET_TODAY_SALES
 * - 「卖两斤土豆」「来两斤土豆」→ ADD_SALE_ITEM
 * - 「土豆多少钱」→ FIND_PRODUCT
 * - 「给张姐充200」→ RECHARGE_MEMBER（金额元→分）
 * - 「进100斤土豆」→ PURCHASE_IN
 */
class LocalRuleParser : AiProvider {

    override fun complete(request: GatewayRequest): AiResponse {
        val text = normalize(request.inputText)
        return when {
            text.contains("卖了多少") || text.contains("卖了多少钱") || text == "今天卖了多少" ->
                AiResponse.ToolCall("get_today_sales", emptyMap())

            else -> parseWithEntity(text)
        }
    }

    private fun parseWithEntity(text: String): AiResponse {
        // 结账/买单（V1 默认现金手工确认；具体方式可显式说明）
        CHECKOUT_PATTERN.find(text)?.let { m ->
            val method = when (m.groupValues[1]) {
                "微信" -> "wechat"
                "支付宝" -> "alipay"
                else -> "cash"
            }
            return AiResponse.ToolCall("checkout_sale", mapOf("payment_method" to method))
        }
        // 卖/来 X斤 商品（X 支持阿拉伯数字与单个中文数字：两/二/三…）
        QUANTITY_PATTERN.find(text)?.let { m ->
            val rawQuantity = m.groupValues[1]
            val quantity = toArabicNumber(rawQuantity) ?: return clarification()
            val unit = m.groupValues[2].ifBlank { "斤" }
            val product = m.groupValues[3].trim()
            return AiResponse.ToolCall(
                "add_sale_item",
                mapOf(
                    "product" to product,
                    "quantity" to "$quantity$unit"
                )
            )
        }
        // 商品 多少钱
        Regex("^(.+?)\\s*多少钱$").find(text)?.let { m ->
            return AiResponse.ToolCall(
                "find_product",
                mapOf("query" to m.groupValues[1].trim())
            )
        }
        // 给 X 充 Y 元
        Regex("^给(.+?)充(\\d+(?:\\.\\d+)?)\\s*元?$").find(text)?.let { m ->
            val member = m.groupValues[1].trim()
            val fen = yuanToFen(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "recharge_member",
                mapOf("member" to member, "amount" to fen.toString())
            )
        }
        // 进 X斤 商品[，成本/进价 Y]
        PURCHASE_PATTERN.find(text)?.let { m ->
            val rawQuantity = m.groupValues[1]
            val quantity = toArabicNumber(rawQuantity) ?: return clarification()
            val unit = m.groupValues[2].ifBlank { "斤" }
            val productAndRest = m.groupValues[3].trim()
            val cost = COST_PATTERN.find(productAndRest)?.groupValues?.get(1)
            // 剥离成本与售价提示，取剩余首段为商品名
            val product = productAndRest
                .replace(COST_PATTERN, "")
                .replace(SALE_PRICE_HINT_PATTERN, "")
                .split('，', ',')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?: return clarification()
            val note = if (SALE_PRICE_HINT_PATTERN.containsMatchIn(productAndRest)) {
                "售价改价请单独说：${product}改成X块X"
            } else {
                null
            }
            return AiResponse.ToolCall(
                "purchase_in",
                mapOf(
                    "product" to product,
                    "quantity" to "$quantity$unit",
                    "cost" to (cost ?: ""),
                    "note" to (note ?: "")
                )
            )
        }
        return clarification()
    }

    /** 单个中文数字（含「两」「半」）转阿拉伯数字；复合数字（如十五）暂不支持。 */
    private fun toArabicNumber(raw: String): String? {
        if (raw.all { it.isDigit() || it == '.' }) return raw
        if (raw.length != 1) return null
        return CHINESE_NUMERALS[raw]?.toString()
    }

    private companion object {
        val CHECKOUT_PATTERN = Regex("^(?:结账|买单|(微信|支付宝|现金)结账)$")
        val QUANTITY_PATTERN =
            Regex("^(?:卖|来)(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$")
        val PURCHASE_PATTERN =
            Regex("^进(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$")
        /** 成本/进价金额：长形态优先，防止 \d+ 提前截断「2块8」 */
        val COST_PATTERN = Regex("(?:成本|进价)\\s*(\\d+块\\d?|\\d+元|\\d+(?:\\.\\d+)?|\\d+)")
        /** 句中附带售价改价要求（V1 单次只执行入库，改价引导单独说），长形态优先 */
        val SALE_PRICE_HINT_PATTERN = Regex("卖\\s*(\\d+块\\d?|\\d+(?:\\.\\d+)?|\\d+)")
        val CHINESE_NUMERALS = mapOf(
            "一" to 1, "两" to 2, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10, "半" to 0.5
        )
    }

    private fun clarification() =
        AiResponse.Clarification("没听懂这句，请换种说法或告诉我：商品、数量、要做什么。")

    /**
     * 元字符串转分（Long），全程无 Float/Double（数据宪法 #2）：
     * 仅支持最多两位小数，如 "200"→20000、"2.5"→250。
     */
    private fun yuanToFen(yuanStr: String): Long? {
        val parts = yuanStr.split(".")
        if (parts.size > 2) return null
        val yuan = parts[0].toLongOrNull() ?: return null
        val fenPart = if (parts.size == 2) {
            if (parts[1].length > 2 || !parts[1].all { it.isDigit() }) return null
            parts[1].padEnd(2, '0')
        } else {
            "00"
        }
        return Math.addExact(Math.multiplyExact(yuan, 100L), fenPart.toLong())
    }
}
