package com.smallshoping.app.ai.orchestrator

/**
 * V1 意图类型：与 Tool 一一对应（AI 选择 Tool 前先产出结构化意图，AI 宪法执行链）。
 */
enum class IntentType(val tool: String, val requiredEntities: Set<String>) {
    // 只读
    FIND_PRODUCT("find_product", setOf("query")),
    FIND_PRODUCT_BY_BARCODE("find_product_by_barcode", setOf("barcode")),
    GET_STOCK("get_stock", emptySet()),
    GET_TODAY_SALES("get_today_sales", emptySet()),
    GET_MONTH_SALES("get_month_sales", emptySet()),
    GET_TOP_PRODUCTS("get_top_products", emptySet()),
    GET_LOW_STOCK("get_low_stock", emptySet()),
    FIND_MEMBER("find_member", setOf("query")),
    GET_MEMBER_BALANCE("get_member_balance", setOf("member")),
    FIND_CUSTOMER("find_customer", setOf("query")),
    GET_CUSTOMER_DEBT("get_customer_debt", setOf("customer")),
    GET_CURRENT_SALE("get_current_sale", emptySet()),
    GET_STOCK_HISTORY("get_stock_history", setOf("product")),
    GET_LOSS_REPORT("get_loss_report", emptySet()),
    GET_PROFIT_SUMMARY("get_profit_summary", emptySet()),
    GET_FULFILLMENT("get_fulfillment", setOf("fulfillment")),
    GET_CONTEXT("get_context", emptySet()),
    // 写
    CREATE_PRODUCT("create_product", setOf("name")),
    PURCHASE_IN("purchase_in", setOf("product", "quantity")),
    CREATE_SALE("create_sale", emptySet()),
    ADD_SALE_ITEM("add_sale_item", setOf("product", "quantity")),
    REORDER_LAST_ITEM("reorder_last_item", setOf("customer", "quantity")),
    REMOVE_SALE_ITEM("remove_sale_item", setOf("product")),
    CHECKOUT_SALE("checkout_sale", setOf("payment_method")),
    CANCEL_SALE("cancel_sale", emptySet()),
    REFUND_SALE("refund_sale", setOf("sale")),
    CHANGE_PRICE("change_price", setOf("product", "price")),
    APPLY_YESTERDAY_PRICE("apply_yesterday_price", emptySet()),
    RECHARGE_MEMBER("recharge_member", setOf("member", "amount")),
    CHARGE_MEMBER("charge_member", setOf("member", "amount")),
    RECORD_CUSTOMER_CREDIT("record_customer_credit", setOf("customer")),
    SETTLE_CUSTOMER_DEBT("settle_customer_debt", setOf("customer", "amount")),
    RECORD_LOSS("record_loss", setOf("product", "quantity")),
    ADJUST_STOCK("adjust_stock", setOf("product", "quantity")),
    CREATE_FULFILLMENT("create_fulfillment", setOf("sale")),
    UPDATE_FULFILLMENT_STATUS("update_fulfillment_status", setOf("fulfillment", "status"));

    companion object {
        fun fromTool(toolName: String): IntentType? = values().firstOrNull { it.tool == toolName }
    }
}

/**
 * 结构化意图：AI 输出必须经 [IntentSchemaValidator] 校验后才能进入 Tool 层
 * （AI 宪法 #1：AI 输出必须 Schema Validate）。
 *
 * @param entities 实体键值（键为实体名，值暂为字符串，数值解析在 Tool 执行前完成）
 * @param confidence 0-1，AI 自评置信度
 * @param clarification 需要追问用户的问题（有值表示本意图不完整，等待人工回答）
 * @param requestId 确认状态机的请求 ID（spec 08 §9），防止确认错对象
 */
data class Intent(
    val type: IntentType,
    val entities: Map<String, String> = emptyMap(),
    val confidence: Double? = null,
    val clarification: String? = null,
    val requestId: String? = null
) {

    init {
        require(confidence == null || confidence in 0.0..1.0) {
            "confidence 须在 0-1：$confidence"
        }
        require(clarification == null || clarification.isNotBlank()) {
            "clarification 不能为空串"
        }
    }
}
