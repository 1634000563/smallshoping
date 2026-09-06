package com.smallshoping.app.ai.tools

import com.smallshoping.app.ai.risk.RiskLevel

/** Tool 目录端口：按引用查契约。 */
interface ToolCatalog {

    fun tool(ref: ToolRef): ToolContract?

    fun all(): List<ToolContract>
}

/**
 * V1 Tool 目录：与 `docs/schemas/tool-catalog.json`（版本 1.0）一一对应。
 * json 是人与机器参考文件，本目录是运行时校验依据；
 * 两者一致性由 ToolCatalogTest 守护，新增 Tool 必须两处同步。
 */
object V1ToolCatalog : ToolCatalog {

    private val byName = buildList {
        fun read(
            name: String,
            required: Set<String>,
            optional: Set<String> = emptySet(),
            successFields: Set<String>,
            errorCodes: Set<String> = setOf("NOT_FOUND")
        ) {
            add(
                ToolContract(
                    ref = ToolRef(name),
                    description = name,
                    inputRequired = required,
                    inputOptional = optional,
                    riskLevel = RiskLevel.LOW,
                    confirmationPolicy = ConfirmationPolicy.NONE,
                    idempotencyRequired = false,
                    allowedOffline = true,
                    auditAction = name,
                    successResultFields = successFields,
                    errorCodes = errorCodes
                )
            )
        }

        fun write(
            name: String,
            risk: RiskLevel,
            policy: ConfirmationPolicy,
            required: Set<String>,
            optional: Set<String> = emptySet(),
            successFields: Set<String>,
            errorCodes: Set<String> = setOf("INVALID_ARGUMENT")
        ) {
            add(
                ToolContract(
                    ref = ToolRef(name),
                    description = name,
                    inputRequired = required,
                    inputOptional = optional,
                    riskLevel = risk,
                    confirmationPolicy = policy,
                    idempotencyRequired = true,
                    allowedOffline = true,
                    auditAction = name,
                    successResultFields = successFields,
                    errorCodes = errorCodes
                )
            )
        }

        // ---- 只读工具（spec 07 §1 Read-only）----
        read("find_product", setOf("query"), successFields = setOf("status", "products"))
        read("find_product_by_barcode", setOf("barcode"), successFields = setOf("status", "product"))
        read("get_stock", emptySet(), optional = setOf("product"), successFields = setOf("status", "stock"))
        read("get_today_sales", emptySet(), successFields = setOf("status", "total_minor", "count"))
        read("get_month_sales", emptySet(), successFields = setOf("status", "total_minor", "count"))
        read("get_top_products", emptySet(), successFields = setOf("status", "products"))
        read("get_low_stock", emptySet(), successFields = setOf("status", "products"))
        read("find_member", setOf("query"), successFields = setOf("status", "members"))
        read("get_member_balance", setOf("member"), successFields = setOf("status", "balance_minor"))
        read("find_customer", setOf("query"), successFields = setOf("status", "customers"))
        read("get_customer_debt", setOf("customer"), successFields = setOf("status", "debt_minor"))
        read("get_current_sale", emptySet(), successFields = setOf("status", "sale"))
        read("get_stock_history", setOf("product"), successFields = setOf("status", "entries"))
        read("get_loss_report", emptySet(), successFields = setOf("status", "entries"))
        read("get_profit_summary", emptySet(), successFields = setOf("status", "summary"))
        read("get_fulfillment", setOf("fulfillment"), successFields = setOf("status", "fulfillment"))
        read("get_context", emptySet(), successFields = setOf("status", "context"))

        // ---- 写工具（spec 07 §1 Mutating）----
        write(
            "create_product", RiskLevel.MEDIUM, ConfirmationPolicy.WHEN_AMBIGUOUS,
            setOf("name"), optional = setOf("unit", "price"),
            successFields = setOf("status", "product_id")
        )
        write(
            "purchase_in", RiskLevel.LOW, ConfirmationPolicy.NONE,
            setOf("product", "quantity"), optional = setOf("cost"),
            successFields = setOf("status", "purchase_id")
        )
        write(
            "create_sale", RiskLevel.LOW, ConfirmationPolicy.NONE,
            emptySet(), optional = setOf("customer", "member"),
            successFields = setOf("status", "sale_id")
        )
        write(
            "add_sale_item", RiskLevel.LOW, ConfirmationPolicy.NONE,
            setOf("product", "quantity"),
            successFields = setOf("status", "item_id"),
            errorCodes = setOf("INVALID_ARGUMENT", "NOT_FOUND", "OUT_OF_STOCK")
        )
        write(
            "reorder_last_item", RiskLevel.LOW, ConfirmationPolicy.NONE,
            setOf("customer", "quantity"), optional = setOf("product"),
            successFields = setOf("status", "item_id"),
            errorCodes = setOf("INVALID_ARGUMENT", "NOT_FOUND")
        )
        write(
            "remove_sale_item", RiskLevel.MEDIUM, ConfirmationPolicy.NONE,
            emptySet(), optional = setOf("product"),
            successFields = setOf("status", "item_id"),
            errorCodes = setOf("INVALID_ARGUMENT", "NOT_FOUND")
        )
        write(
            "checkout_sale", RiskLevel.MEDIUM, ConfirmationPolicy.PAYMENT_CONFIRMATION,
            setOf("payment_method"),
            successFields = setOf("status", "sale_id", "paid_minor")
        )
        write(
            "cancel_sale", RiskLevel.MEDIUM, ConfirmationPolicy.NONE,
            emptySet(),
            successFields = setOf("status", "sale_id")
        )
        write(
            "refund_sale", RiskLevel.MEDIUM, ConfirmationPolicy.REQUIRED,
            setOf("sale"),
            successFields = setOf("status", "refund_id")
        )
        write(
            "change_price", RiskLevel.MEDIUM, ConfirmationPolicy.LARGE_DELTA,
            setOf("product", "price"),
            successFields = setOf("status", "product_id", "old_price_minor", "new_price_minor")
        )
        write(
            "apply_yesterday_price", RiskLevel.MEDIUM, ConfirmationPolicy.REQUIRED,
            emptySet(), optional = setOf("price"),
            successFields = setOf("status", "product_id", "new_price_minor"),
            errorCodes = setOf("INVALID_ARGUMENT", "NOT_FOUND")
        )
        write(
            "recharge_member", RiskLevel.MEDIUM, ConfirmationPolicy.REQUIRED,
            setOf("member", "amount"),
            successFields = setOf("status", "member_id", "balance_after_minor")
        )
        write(
            "charge_member", RiskLevel.MEDIUM, ConfirmationPolicy.REQUIRED,
            setOf("member", "amount"),
            successFields = setOf("status", "member_id", "balance_after_minor"),
            errorCodes = setOf("INVALID_ARGUMENT", "NOT_FOUND", "INSUFFICIENT_BALANCE")
        )
        write(
            "record_customer_credit", RiskLevel.MEDIUM, ConfirmationPolicy.WHEN_AMBIGUOUS,
            setOf("customer"), optional = setOf("amount"),
            successFields = setOf("status", "customer_id", "debt_after_minor"),
            errorCodes = setOf("INVALID_ARGUMENT", "NOT_FOUND")
        )
        write(
            "settle_customer_debt", RiskLevel.MEDIUM, ConfirmationPolicy.REQUIRED,
            setOf("customer", "amount"),
            successFields = setOf("status", "customer_id", "debt_after_minor")
        )
        write(
            "record_loss", RiskLevel.MEDIUM, ConfirmationPolicy.REQUIRED,
            setOf("product", "quantity"),
            successFields = setOf("status", "loss_id")
        )
        write(
            "adjust_stock", RiskLevel.HIGH, ConfirmationPolicy.REQUIRED,
            setOf("product", "quantity"),
            successFields = setOf("status", "product_id", "stock_after")
        )
        write(
            "create_fulfillment", RiskLevel.LOW, ConfirmationPolicy.NONE,
            setOf("sale"),
            successFields = setOf("status", "fulfillment_id")
        )
        write(
            "update_fulfillment_status", RiskLevel.MEDIUM, ConfirmationPolicy.NONE,
            setOf("fulfillment", "status"),
            successFields = setOf("status", "fulfillment_id")
        )
    }.associateBy { it.ref.fullName }

    override fun tool(ref: ToolRef): ToolContract? = byName[ref.fullName]

    override fun all(): List<ToolContract> = byName.values.toList()
}
