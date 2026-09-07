package com.smallshoping.app.ai.providers

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.MoneyParser

/**
 * 本地规则解析 Provider（spec 05 §5：简单命令优先本地 parser）。
 *
 * 确定性、零网络、零密钥；只覆盖少数高频句式，复杂表达交云端模型。
 * 解析失败返回 [AiResponse.Clarification]，绝不猜测（产品宪法 #9）。
 *
 * 支持的句式（V1 最小集，随 Task 014/025/056 扩展）：
 * - 「今天卖了多少钱」→ GET_TODAY_SALES
 * - 「卖两斤土豆」「来两斤土豆」→ ADD_SALE_ITEM
 * - 「土豆多少钱」→ FIND_PRODUCT
 * - 「给张姐充200」→ RECHARGE_MEMBER（金额元→分）
 * - 「进100斤土豆」「进100斤土豆，2块8」→ PURCHASE_IN（进价可带「成本/进价」或紧跟逗号）
 * - 「微信。」「微信结账」→ CHECKOUT_SALE（裸支付词结账，spec 17 路径 A）
 * - 「建商品螺丝，卖5块」→ CREATE_PRODUCT（Task 057 无网新建商品，卖价可选）
 */
class LocalRuleParser : AiProvider {

    override fun complete(request: GatewayRequest): AiResponse {
        val text = preprocess(normalize(request.inputText))
        return when {
            // 删除类语句：V1 确定性拒绝（spec 04 事实只追加不删除；spec 08 §8 禁止物理删除）
            text.contains("删除") || text.contains("删掉") || text.contains("抹掉") ->
                AiResponse.FinalText(
                    "账务事实只追加不删除，V1 不提供删除。如需修正请说「损耗/调整/退款」。"
                )

            // 「本月」月报优先于「今天卖了多少钱」的 contains 匹配（Task 059）
            MONTH_SALES_PATTERN.matches(text) ->
                AiResponse.ToolCall("get_month_sales", emptyMap())

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
        // 「微信。」口语结账（spec 17 路径 A）：只说支付方式即按该方式结账，
        // 无待结账单时由 Handler 明确提示（不猜测不伪造）
        BARE_PAYMENT_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall("checkout_sale", mapOf("payment_method" to paymentMethod(m.groupValues[1])))
        }
        // 张姐买单（Task 059：报会员名用余额结账）
        MEMBER_PAY_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "checkout_sale",
                mapOf("payment_method" to "member", "member" to m.groupValues[1].trim())
            )
        }
        // 结账/买单/结一下账（V1 默认现金手工确认；具体方式可显式说明）
        CHECKOUT_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall("checkout_sale", mapOf("payment_method" to paymentMethod(m.groupValues[1])))
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
            val fen = parseMoney(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "record_customer_credit",
                mapOf("customer" to customer, "amount" to fen.toString())
            )
        }
        SETTLE_PATTERN.find(text)?.let { m ->
            val customer = m.groupValues[1].trim()
            val fen = parseMoney(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "settle_customer_debt",
                mapOf("customer" to customer, "amount" to fen.toString())
            )
        }
        // 刚才那个不要了/去掉/退了（Task 038/059：移除最近商品变体）
        REMOVE_LAST_PATTERN.find(text)?.let {
            return AiResponse.ToolCall("remove_sale_item", emptyMap())
        }
        // 不是土豆，是红薯（Task 058，spec 12 §6 纠错）：先把错的拿掉，
        // 不创建错误交易；新商品老板再说一遍（两步纠错，最小诚实实现）
        CORRECTION_PATTERN.find(text)?.let {
            return AiResponse.ToolCall("remove_sale_item", emptyMap())
        }
        // 土豆改价三块五 / 土豆改成四十块 / 土豆价格改为三块八 / 土豆的销售价格是四块（Task 059）
        CHANGE_PRICE_PATTERN.find(text)?.let { m ->
            val product = m.groupValues[1].trim()
            val fen = parseMoney(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "change_price",
                mapOf("product" to product, "price" to fen.toString())
            )
        }
        // 土豆价格是四块（「是」形改价，Task 059）
        PRICE_IS_PATTERN.find(text)?.let { m ->
            val product = m.groupValues[1].trim()
            val fen = parseMoney(m.groupValues[2]) ?: return clarification()
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
        // 张姐余额多少（Task 059：余额句式）
        MEMBER_BALANCE_SHORT_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "get_member_balance",
                mapOf("member" to m.groupValues[1].trim())
            )
        }
        // 土豆还有多少货/库存（Task 059：库存查询）
        STOCK_QUERY_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "get_stock",
                mapOf("product" to cleanProductQuery(m.groupValues[1]))
            )
        }
        // 老张欠多少钱（Task 059：欠款查询）
        DEBT_QUERY_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "get_customer_debt",
                mapOf("customer" to m.groupValues[1].trim())
            )
        }
        // 现在有什么（Task 059：当前单查询）
        CURRENT_SALE_PATTERN.find(text)?.let {
            return AiResponse.ToolCall("get_current_sale", emptyMap())
        }
        // 今天赚了多少 / 哪些货快没了（Task 059：报表变体；本月句在 complete() 优先匹配）
        PROFIT_PATTERN.find(text)?.let { return AiResponse.ToolCall("get_profit_summary", emptyMap()) }
        LOW_STOCK_PATTERN.find(text)?.let { return AiResponse.ToolCall("get_low_stock", emptyMap()) }
        // 倒装入库：土豆进了100斤（Task 059，置于数量后置之前防误判为售卖）
        INVERTED_PURCHASE_PATTERN.find(text)?.let { m ->
            val product = m.groupValues[1].trim()
            val quantity = toArabicNumber(m.groupValues[2]) ?: return clarification()
            val unit = m.groupValues[3].ifBlank { "斤" }
            return AiResponse.ToolCall(
                "purchase_in",
                mapOf("product" to product, "quantity" to "$quantity$unit", "cost" to "", "note" to "")
            )
        }
        // 土豆多少钱一斤（报价查询，置于数量后置之前防误判）
        PRICE_QUERY_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "find_product",
                mapOf("query" to cleanProductQuery(m.groupValues[1]))
            )
        }
        // 倒装损耗：半斤土豆坏了（Task 059，置于数量前置之前防误判为售卖）
        INVERTED_LOSS_PATTERN.find(text)?.let { m ->
            val quantity = toArabicNumber(m.groupValues[1]) ?: return clarification()
            val unit = m.groupValues[2].ifBlank { "斤" }
            val product = m.groupValues[3].trim()
            return AiResponse.ToolCall(
                "record_loss",
                mapOf("product" to product, "quantity" to "$quantity$unit")
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
        // 卖/来/加 X斤 商品（Task 059：支持「卖了」「加了」与「加」句式）
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
        // 商品 多少钱（已上移至数量后置之前；此处仅保留无单位基础形兜底）
        PRICE_QUERY_PATTERN.find(text)?.let { m ->
            return AiResponse.ToolCall(
                "find_product",
                mapOf("query" to cleanProductQuery(m.groupValues[1]))
            )
        }
        // 给 X 充 Y 元（金额支持 200 / 2块8 / 两百 / 两百块钱；「充钱/充费」兼容）
        RECHARGE_PATTERN.find(text)?.let { m ->
            val member = m.groupValues[1].trim()
            val fen = parseMoney(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "recharge_member",
                mapOf("member" to member, "amount" to fen.toString())
            )
        }
        // 建商品螺丝，卖5块（Task 057：无网新建商品，非 AI 兜底完整性；
        // 卖价可选，未给按 0 元入库再由老板改价，绝不猜测）
        CREATE_PRODUCT_PATTERN.find(text)?.let { m ->
            val name = m.groupValues[1].trim()
            val price = m.groupValues[2].takeIf { it.isNotBlank() }
                ?.let { raw -> MoneyParser.parseYuanToMinor(raw) ?: return clarification() }
            return AiResponse.ToolCall(
                "create_product",
                mapOf("name" to name) + if (price != null) mapOf("price" to price.toString()) else emptyMap()
            )
        }
        // 进 X斤 商品[，成本/进价 Y]
        PURCHASE_PATTERN.find(text)?.let { m ->
            val rawQuantity = m.groupValues[1]
            val quantity = toArabicNumber(rawQuantity) ?: return clarification()
            val unit = m.groupValues[2].ifBlank { "斤" }
            val productAndRest = m.groupValues[3].trim()
            val cost = COST_PATTERN.find(productAndRest)?.groupValues?.get(1)
                // spec 17 路径 A：「进100斤土豆，2块8」——进价可紧跟逗号省略「成本/进价」
                ?: COMMA_COST_PATTERN.find(productAndRest)?.groupValues?.get(1)
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
                    // 中文金额（两块八）转元文本交 Handler；阿拉伯形态原样透传
                    "cost" to (cost?.let { chineseMoneyToMinor(it)?.let { fen -> fenToYuanText(fen) } ?: it } ?: ""),
                    "note" to (note ?: "")
                )
            )
        }
        // 土豆卖四块（Task 059：「卖」形改价，置于采购后防误吞）
        SELL_PRICE_PATTERN.find(text)?.let { m ->
            val product = m.groupValues[1].trim()
            val fen = parseMoney(m.groupValues[2]) ?: return clarification()
            return AiResponse.ToolCall(
                "change_price",
                mapOf("product" to product, "price" to fen.toString())
            )
        }
        return clarification()
    }

    /** 查询词清洗：去掉「卖/的/价格」等尾缀（Task 059 语音口语）。 */
    private fun cleanProductQuery(raw: String): String =
        raw.trim().removeSuffix("卖").removeSuffix("的").removeSuffix("价格").trim()

    /** 支付方式词 → 支付方式编码（结账两类句式共用）。 */
    private fun paymentMethod(raw: String): String = when (raw) {
        "微信" -> "wechat"
        "支付宝" -> "alipay"
        "会员" -> "member"
        else -> "cash"
    }

    /**
     * 语音键盘输出的纠错预处理（Task 059 真机验收）：
     * 去句尾标点、口语助词前缀/后缀、常见同音字——让语音直出的句子
     * 也能命中确定性句式。保守白名单，不做语义猜测。
     */
    private fun preprocess(raw: String): String {
        var t = raw.trim()
        t = t.replace(Regex("[。！？!?；;]+$"), "")
        t = t.replace(Regex("^(我想|我想说|帮我|给我|麻烦|请帮我|我要|把)"), "")
        t = t.replace(Regex("(吧|呢|啊|呀|哈|一下|谢谢)$"), "")
        t = t.replace("结帐", "结账")
        return t
    }

    /** 分 → 元文本（如 280 → "2.80"），供 Handler 的元解析复用；纯整数运算。 */
    private fun fenToYuanText(fen: Long): String =
        if (fen % 100L == 0L) "${fen / 100L}"
        else "${fen / 100L}.${(fen % 100L).toString().padStart(2, '0')}"

    /** 金额 → 分：中文形态（两块八/两百/五毛）先解析，否则交 MoneyParser（阿拉伯形态）。 */
    private fun parseMoney(raw: String): Long? =
        chineseMoneyToMinor(raw) ?: MoneyParser.parseYuanToMinor(raw)

    /**
     * 中文金额 → 分（纯整数运算）：两块八→280、三块五→350、
     * 两百→20000、一百零二→10200、五毛→50；非中文金额返回 null。
     */
    private fun chineseMoneyToMinor(raw: String): Long? {
        var r = raw.trim().removeSuffix("元")
        if (r.isEmpty() || r.all { it.isDigit() || it == '.' }) return null // 阿拉伯形态交 MoneyParser
        val kuai = Regex("^(.+?)块(.*)$").find(r)
        if (kuai != null) {
            val yuan = chineseToInt(kuai.groupValues[1]) ?: return null
            val rest = kuai.groupValues[2]
            val jiaoFen = when {
                rest.isEmpty() -> 0L
                rest == "半" -> 50L
                rest.length == 1 -> (chineseDigit(rest) ?: return null) * 10L
                rest.length == 2 -> {
                    val d1 = chineseDigit(rest[0].toString()) ?: return null
                    val d2 = chineseDigit(rest[1].toString()) ?: return null
                    d1 * 10L + d2
                }
                else -> return null
            }
            return Math.addExact(Math.multiplyExact(yuan, 100L), jiaoFen)
        }
        val mao = Regex("^(.+?)毛$").find(r)
        if (mao != null) {
            return Math.multiplyExact(chineseToInt(mao.groupValues[1]) ?: return null, 10L)
        }
        return chineseToInt(r)?.let { Math.multiplyExact(it, 100L) }
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

    /** 复合中文数字转整数（纯整数运算）：四十→40、十五→15、四十五→45、两百→200、
     *  两百五→250（口语省十）、两百五十→250、一百零二→102、三→3。 */
    private fun chineseToInt(raw: String): Long? {
        if (raw.length == 1) return chineseDigit(raw)
        return when {
            raw.length == 2 && raw[1] == '百' ->
                chineseDigit(raw[0].toString())?.let { it * 100 }
            raw.length == 4 && raw[1] == '百' && raw[2] == '零' ->
                chineseDigit(raw[0].toString())?.let { a ->
                    chineseDigit(raw[3].toString())?.let { a * 100 + it }
                }
            raw.length == 3 && raw[1] == '百' ->
                chineseDigit(raw[0].toString())?.let { a ->
                    chineseDigit(raw[2].toString())?.let { a * 100 + it * 10 }
                }
            raw.length == 4 && raw[1] == '百' && raw[3] == '十' ->
                chineseDigit(raw[0].toString())?.let { a ->
                    chineseDigit(raw[2].toString())?.let { a * 100 + it * 10 }
                }
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
        /** 阿拉伯金额形态（长形态优先，防止 \d+ 提前截断「2块8」）。 */
        val ARABIC_MONEY = "\\d+块\\d?毛?|\\d+元|\\d+(?:\\.\\d+)?|\\d+"
        /** 中文金额形态（语音键盘直出：两块八/两百/五毛）。 */
        val CHINESE_MONEY = "[一两二三四五六七八九十百零半]+(?:块[一两二三四五六七八九半零]*|毛|元)?"
        // 「结帐」为常见同音/异体误写，兼容之
        val CHECKOUT_PATTERN = Regex("^(?:结(?:一下)?账|结帐|买单|(微信|支付宝|现金|会员)(?:结账|结帐))$")
        /** 张姐买单：会员余额结账（Task 059） */
        val MEMBER_PAY_PATTERN = Regex("^(.+?)买单$")
        /** 裸支付词结账（spec 17 路径 A：「微信。」）；无单场景由 Handler 明确提示。 */
        val BARE_PAYMENT_PATTERN = Regex("^(微信|支付宝|现金|会员)$")
        val QUANTITY_PATTERN =
            Regex("^(?:卖|来|加|称|再来)了?(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$")
        // 「近」是键盘语音对「进」的常见同音误识别；「进了/进货/采购」口语兼容（Task 059）
        val PURCHASE_PATTERN =
            Regex("^(?:进|近|进货|采购)[了]?(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$")
        /** 倒装入库：土豆进了100斤（Task 059） */
        val INVERTED_PURCHASE_PATTERN = Regex(
            "^(.+?)[进近]了?(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)?$"
        )
        /** 补单：老张上次那些螺丝再来两盒（商品名可省略，由客户记忆兜底） */
        val REORDER_PATTERN = Regex(
            "^(.+?)上次(?:那些|那个|的)?\\s*(.*?)再来" +
                "(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*(斤|公斤|kg|克|个|盒|米)$"
        )
        /** 欠款：老张先记账（金额取草稿单） */
        val CREDIT_DRAFT_PATTERN = Regex("^(.+?)先记账$")
        /** 欠款：老张赊200 / 老张赊账2块8 / 老张欠两百（Task 059：赊/欠、账/钱兼容） */
        val CREDIT_AMOUNT_PATTERN = Regex(
            "^(.+?)(?:赊|欠)(?:账|钱)?($ARABIC_MONEY|$CHINESE_MONEY)(?:钱)?\\s*元?$"
        )
        /** 收款：老张还100 / 老张还一百 / 老张还钱100（Task 059） */
        val SETTLE_PATTERN = Regex(
            "^(.+?)还(?:钱|款)?($ARABIC_MONEY|$CHINESE_MONEY)\\s*元?$"
        )
        /** 条码：8-14 位纯数字（EAN-13/Code128 常见长度） */
        val BARCODE_PATTERN = Regex("^\\d{8,14}$")
        /** 移除最近商品：刚才那个不要了/去掉/退了 */
        val REMOVE_LAST_PATTERN = Regex("^(?:刚才那个|刚那个)(?:不要了|去掉|退了)$")
        /** 纠错句：不是X，是Y（spec 12 §6：先拿掉错的，再重说新的） */
        val CORRECTION_PATTERN = Regex("^不是.+?(?:，|,)?是.+$")
        /** 改价：土豆改价三块五 / 土豆改成四十块 / 土豆价格改为三块八（Task 059：改为/改到兼容） */
        val CHANGE_PRICE_PATTERN = Regex(
            "^(.+?)(?:的)?(?:价格)?改(?:价|成|为|到)\\s*($ARABIC_MONEY|$CHINESE_MONEY)\\s*元?$"
        )
        /** 改价「是」形：土豆的销售价格是四块 / 土豆价格是四块（Task 059） */
        val PRICE_IS_PATTERN = Regex(
            "^(.+?)(?:的)?(?:销售)?价格是\\s*($ARABIC_MONEY|$CHINESE_MONEY)\\s*元?$"
        )
        /** 改价「卖」形：土豆卖四块（置于采购句式之后防误吞「进100斤土豆卖3块8」） */
        val SELL_PRICE_PATTERN = Regex(
            "^(.+?)卖\\s*($ARABIC_MONEY|$CHINESE_MONEY)\\s*元?$"
        )
        /** 报价查询：土豆多少钱 / 土豆多少钱一斤 / 土豆卖多少钱（Task 059 带单位与口语尾缀） */
        val PRICE_QUERY_PATTERN = Regex(
            "^(.+?)多少钱(?:一斤|一个|一盒|一块|一公斤)?$"
        )
        /** 会员余额：张姐还有多少钱 */
        val MEMBER_BALANCE_PATTERN = Regex("^(.+?)还有多少钱$")
        /** 会员余额短形：张姐余额多少（Task 059） */
        val MEMBER_BALANCE_SHORT_PATTERN = Regex("^(.+?)(?:的)?余额(?:有多少|多少)?$")
        /** 库存查询：土豆还有多少货/库存多少（Task 059） */
        val STOCK_QUERY_PATTERN = Regex("^(.+?)(?:还有多少(?:货|库存)?|库存(?:还有)?多少)$")
        /** 欠款查询：老张欠多少钱（Task 059） */
        val DEBT_QUERY_PATTERN = Regex("^(.+?)欠(?:了)?多少(?:钱)?$")
        /** 当前单查询：现在有什么（Task 059） */
        val CURRENT_SALE_PATTERN = Regex("^(?:现在|当前)有什么(?:货|单)?$")
        /** 毛利查询：今天赚了多少（Task 059） */
        val PROFIT_PATTERN = Regex("^(?:今天)?赚(?:了)?多少(?:钱)?$")
        /** 月报：本月卖了多少钱（Task 059） */
        val MONTH_SALES_PATTERN = Regex("^(?:本月|这个月)(?:卖了多少钱|卖了多少|卖了多少货)?$")
        /** 缺货：哪些货快没了（Task 059） */
        val LOW_STOCK_PATTERN = Regex("^(?:哪些|什么)(?:货|商品)(?:快没了|不多了|缺货)$")
        /** 数量前置：两斤半土豆（X量 商品，量可带「半」，单位必填防误吞条码/报价句） */
        val LEADING_QUANTITY_PATTERN = Regex(
            "^(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)(半)?\\s*(.+)$"
        )
        /** 数量后置：土豆两斤六 / 土豆要两斤（商品+数量，尾数字为 0.x 单位；要/来/卖可选） */
        val TRAILING_QUANTITY_PATTERN = Regex(
            "^(.+?)(?:要|来|卖)?([一两二三四五六七八九十]|\\d+(?:\\.\\d+)?)\\s*" +
                "(斤|公斤|kg|克|个|盒|米)([一二三四五六七八九半])?$"
        )
        /** 损耗：损耗两斤土豆 / 坏了半斤土豆（单位缺省按斤，Task 059） */
        val LOSS_PATTERN = Regex(
            "^(?:损耗|坏了)(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)?\\s*(.+)$"
        )
        /** 倒装损耗：半斤土豆坏了（Task 059，置于数量前置之前） */
        val INVERTED_LOSS_PATTERN = Regex(
            "^(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)(.+?)坏了$"
        )
        /** 损耗：土豆坏了2斤 */
        val LOSS_BROKEN_PATTERN = Regex(
            "^(.+?)坏了(\\d+(?:\\.\\d+)?|[一两二三四五六七八九十半])\\s*" +
                "(斤|公斤|kg|克|个|盒|米)?$"
        )
        /** 充值金额：长形态优先（块毛>元>小数>整数），防 \d+ 提前截断「2块8」；含中文金额与「充钱/两百块钱」 */
        val RECHARGE_PATTERN =
            Regex("^给?(.+?)充(?:钱|费)?($ARABIC_MONEY|$CHINESE_MONEY)(?:钱|费)?\\s*元?$")
        /** 建商品（Task 057）：建[一个/个]商品[叫]X[，卖Y]；卖价可选 */
        val CREATE_PRODUCT_PATTERN = Regex(
            "^建(?:一个|个)?商品(?:叫)?(.+?)(?:，?\\s*卖\\s*(\\d+块\\d?毛?|\\d+元|\\d+(?:\\.\\d+)?|\\d+))?$"
        )
        /** 成本/进价金额。 */
        val COST_PATTERN = Regex("(?:成本|进价)\\s*($ARABIC_MONEY|$CHINESE_MONEY)")
        /** 逗号后裸进价（spec 17 路径 A：「进100斤土豆，2块8」「…，两块八」）。 */
        val COMMA_COST_PATTERN = Regex("(?:，|,)\\s*($ARABIC_MONEY|$CHINESE_MONEY)")
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
