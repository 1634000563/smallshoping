package com.smallshoping.app.ai.providers

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.MoneyParser

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
            // 删除类语句：V1 确定性拒绝（spec 04 事实只追加不删除；spec 08 §8 禁止物理删除）
            text.contains("删除") || text.contains("删掉") || text.contains("抹掉") ->
                AiResponse.FinalText(
                    "账务事实只追加不删除，V1 不提供删除。如需修正请说「损耗/调整/退款」。"
                )

            text.contains("卖了多少") || text.contains("卖了多少钱") || text == "今天卖了多少" ->
                AiResponse.ToolCall("get_today_sales", emptyMap())

            // 「还是昨天那个价格」「用昨天的价格」（Task 026：商品取会话上下文，价格查昨日历史）
            text.contains("昨天") && text.contains("价") ->
                AiResponse.ToolCall("apply_yesterday_price", emptyMap())

            // 扫码输入：8-14 位纯数字（Task 036 条码通用输入，确定性路径）
            BARCODE_PATTERN.matches(text) ->
                AiResponse.ToolCall("find_product_by_barcode", mapOf("barcode" to text))

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
        // 老张上次那些螺丝再来两盒（Task 027：客户+商品+数量补单）
        REORDER_PATTERN.find(text)?.let { m ->
            val customer = m.groupValues[1].trim()
            val product = m.groupValues[2].trim()
            val rawQuantity = m.groupValues[3]
            val unit = m.groupValues[4].ifBlank { "盒" }
            val quantity = toArabicNumber(rawQuantity) ?: return clarification()
            return AiResponse.ToolCall(
                "reorder_last_item",
                mapOf(
                    "customer" to customer,
                    "quantity" to "$quantity$unit"
                ) + if (product.isNotBlank()) mapOf("product" to product) else emptyMap()
            )
        }
        // 老张先记账 / 老张赊200 / 老张还100（Task 034 客户欠款）
        CREDIT_DRAFT_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "record_customer_credit",
                mapOf("customer" to m.groupValues[1].trim())
            )
        }
        CREDIT_AMOUNT_PATTERN.find(text)?.let { m ->
            val customer = m.groupValues[1].trim()
            val fen = MoneyParser.parseYuanToMinor(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "record_customer_credit",
                mapOf("customer" to customer, "amount" to fen.toString())
            )
        }
        SETTLE_PATTERN.find(text)?.let { m ->
            val customer = m.groupValues[1].trim()
            val fen = MoneyParser.parseYuanToMinor(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "settle_customer_debt",
                mapOf("customer" to customer, "amount" to fen.toString())
            )
        }
        // 刚才那个不要了（Task 038：移除最近商品）
        REMOVE_LAST_PATTERN.find(text)?.let {
            return AiResponse.ToolCall("remove_sale_item", emptyMap())
        }
        // 土豆改价三块五 / 土豆改成四十块（Task 038：改价）
        CHANGE_PRICE_PATTERN.find(text)?.let { m ->
            val product = m.groupValues[1].trim()
            val rawMoney = m.groupValues[2]
            val fen = if (m.groupValues[3].isNotBlank()) {
                // 中文块形态：三块五 → 350 分；四十块 → 4000 分（纯整数运算）
                val yuan = chineseToInt(m.groupValues[3])
                    ?: return clarification()
                val jiao = m.groupValues[4].takeIf { it.isNotBlank() }
                    ?.let { chineseDigit(it) } ?: 0L
                Math.addExact(Math.multiplyExact(yuan, 100L), Math.multiplyExact(jiao, 10L))
            } else {
                // 阿拉伯金额转分（三块五等已在上支处理）
                val arabic = rawMoney.map { c ->
                    CHINESE_NUMERALS[c.toString()]?.toString() ?: c.toString()
                }.joinToString("")
                MoneyParser.parseYuanToMinor(arabic) ?: return clarification()
            }
            return AiResponse.ToolCall(
                "change_price",
                mapOf("product" to product, "price" to fen.toString())
            )
        }
        // 张姐还有多少钱（Task 038：会员余额）
        MEMBER_BALANCE_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "get_member_balance",
                mapOf("member" to m.groupValues[1].trim())
            )
        }
        // 数量前置：两斤半土豆（Task 038，无「卖/来」前缀）
        LEADING_QUANTITY_PATTERN.find(text)?.let { m ->
            val num = toArabicNumber(m.groupValues[1]) ?: return clarification()
            val unit = m.groupValues[2].ifBlank { "斤" }
            val half = m.groupValues[3]
            // 纯字符串拼接（无浮点，数据宪法 #2）；带「半」只允许整数前缀
            val quantity = if (half.isNotBlank()) {
                if (num.contains('.')) return clarification()
                "$num.5$unit"
            } else {
                "$num$unit"
            }
            val product = m.groupValues[4].trim()
            return AiResponse.ToolCall(
                "add_sale_item",
                mapOf("product" to product, "quantity" to quantity)
            )
        }
        // 损耗两斤土豆 / 土豆坏了2斤（Task 033 生鲜损耗）
        LOSS_PATTERN.find(text)?.let { m ->
            val quantity = toArabicNumber(m.groupValues[1]) ?: return clarification()
            val unit = m.groupValues[2].ifBlank { "斤" }
            val product = m.groupValues[3].trim()
            return AiResponse.ToolCall(
                "record_loss",
                mapOf("product" to product, "quantity" to "$quantity$unit")
            )
        }
        LOSS_BROKEN_PATTERN.find(text)?.let { m ->
            val quantity = toArabicNumber(m.groupValues[2]) ?: return clarification()
            val unit = m.groupValues[3].ifBlank { "斤" }
            val product = m.groupValues[1].trim()
            return AiResponse.ToolCall(
                "record_loss",
                mapOf("product" to product, "quantity" to "$quantity$unit")
            )
        }
        // 数量后置：土豆两斤六（Task 038，两斤六=2.6斤；损耗句式优先）
        TRAILING_QUANTITY_PATTERN.find(text)?.let { m ->
            val num = toArabicNumber(m.groupValues[2]) ?: return clarification()
            val unit = m.groupValues[3].ifBlank { "斤" }
            val fraction = m.groupValues[4]
            val quantity = if (fraction.isBlank()) {
                "$num$unit"
            } else {
                if (num.contains('.')) return clarification()
                val fracDigit = if (fraction == "半") "5" else CHINESE_NUMERALS[fraction]?.toString()
                    ?: return clarification()
                "$num.$fracDigit$unit"
            }
            val product = m.groupValues[1].trim()
            return AiResponse.ToolCall(
                "add_sale_item",
                mapOf("product" to product, "quantity" to quantity)
            )
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
        // 给 X 充 Y 元（「给」可省略；金额支持 200 / 200元 / 2块8）
        RECHARGE_PATTERN.find(text)?.let { m ->
            val member = m.groupValues[1].trim()
            val fen = MoneyParser.parseYuanToMinor(m.groupValues[2]) ?: return clarification()
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

    /** 单个中文整数数字（不含「半」）。 */
    private fun chineseDigit(raw: String): Long? = when (raw) {
        "一" -> 1L; "两" -> 2L; "二" -> 2L; "三" -> 3L; "四" -> 4L; "五" -> 5L
        "六" -> 6L; "七" -> 7L; "八" -> 8L; "九" -> 9L
        else -> null
    }

    /** 复合中文数字转整数（纯整数运算）：四十→40、十五→15、四十五→45、三→3。 */
    private fun chineseToInt(raw: String): Long? {
        if (raw.length == 1) return chineseDigit(raw)
        return when {
            raw.length == 2 && raw[1] == '十' ->
                chineseDigit(raw[0].toString())?.let { it * 10 }
            raw.length == 2 && raw[0] == '十' ->
                10 + (chineseDigit(raw[1].toString()) ?: return null)
            raw.length == 3 && raw[1] == '十' ->
                (chineseDigit(raw[0].toString()) ?: return null) * 10 +
                    (chineseDigit(raw[2].toString()) ?: return null)
            else -> null
        }
    }

    private companion object {
        val CHECKOUT_PATTERN = Regex("^(?:结账|买单|(微信|支付宝|现金)结账)$")
        val QUANTITY_PATTERN =
            Regex("^(?:卖|来)(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$")
        val PURCHASE_PATTERN =
            Regex("^进(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$")
        /** 补单：老张上次那些螺丝再来两盒（商品名可省略，由客户记忆兜底） */
        val REORDER_PATTERN = Regex(
            "^(.+?)上次(?:那些|那个|的)?\\s*(.*?)再来" +
                "(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)$"
        )
        /** 欠款：老张先记账（金额取草稿单） */
        val CREDIT_DRAFT_PATTERN = Regex("^(.+?)先记账$")
        /** 欠款：老张赊200 / 老张赊账2块8 */
        val CREDIT_AMOUNT_PATTERN = Regex(
            "^(.+?)赊(?:账)?(\\d+块\\d?毛?|\\d+元|\\d+(?:\\.\\d+)?|\\d+)\\s*元?$"
        )
        /** 收款：老张还100 */
        val SETTLE_PATTERN = Regex(
            "^(.+?)还(\\d+块\\d?毛?|\\d+元|\\d+(?:\\.\\d+)?|\\d+)\\s*元?$"
        )
        /** 条码：8-14 位纯数字（EAN-13/Code128 常见长度） */
        val BARCODE_PATTERN = Regex("^\\d{8,14}$")
        /** 移除最近商品：刚才那个不要了 */
        val REMOVE_LAST_PATTERN = Regex("^(?:刚才那个|刚那个)不要了$")
        /** 改价：土豆改价三块五 / 土豆改成四十块 / 土豆改价3.5 */
        val CHANGE_PRICE_PATTERN = Regex(
            "^(.+?)改(?:价|成)\\s*(([一两二三四五六七八九十]{1,3})块([一两二三四五六七八九十])?|" +
                "[一两二三四五六七八九十]块[一两二三四五六七八九十]?|\\d+块\\d?毛?|\\d+元|\\d+(?:\\.\\d+)?|\\d+)\\s*元?$"
        )
        /** 会员余额：张姐还有多少钱 */
        val MEMBER_BALANCE_PATTERN = Regex("^(.+?)还有多少钱$")
        /** 数量前置：两斤半土豆（X量 商品，量可带「半」，单位必填防误吞条码/报价句） */
        val LEADING_QUANTITY_PATTERN = Regex(
            "^(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)(半)?\\s*(.+)$"
        )
        /** 数量后置：土豆两斤六（商品+数量，尾数字为 0.x 单位） */
        val TRAILING_QUANTITY_PATTERN = Regex(
            "^(.+?)([一两二三四五六七八九十]|\\d+(?:\\.\\d+)?)\\s*" +
                "(斤|公斤|kg|克|个|盒|米)([一二三四五六七八九半])?$"
        )
        /** 损耗：损耗两斤土豆（单位缺省按斤） */
        val LOSS_PATTERN = Regex(
            "^损耗(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$"
        )
        /** 损耗：土豆坏了2斤 */
        val LOSS_BROKEN_PATTERN = Regex(
            "^(.+?)坏了(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)?$"
        )
        /** 充值金额：长形态优先（块毛>元>小数>整数），防止 \d+ 提前截断「2块8」 */
        val RECHARGE_PATTERN =
            Regex("^给?(.+?)充(\\d+块\\d?毛?|\\d+元|\\d+(?:\\.\\d+)?|\\d+)\\s*元?$")
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
}
