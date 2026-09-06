package com.smallshoping.app.domain.customer

/** 客户状态（spec 03 §4 customer.status 最小子集）。 */
enum class CustomerStatus { ACTIVE, DISABLED }

/**
 * 客户账户（spec 03 §4 customer 表）：
 * id, name, phone, alias, status, credit_enabled, created_at, updated_at。
 *
 * 欠款不是客户本体字段——必须由 customer_ledger 流水派生（spec 04 §7），
 * 任何页面/UseCase 都不得直接读改写一个「欠款字段」。
 *
 * [creditEnabled] 仅作数据建模（规格未定义其行为门槛，不发明产品规则）。
 */
data class Customer(
    val id: String,
    val storeId: String,
    val name: String,
    val normalizedName: String,
    val phone: String? = null,
    val alias: String? = null,
    val status: CustomerStatus = CustomerStatus.ACTIVE,
    val creditEnabled: Boolean = false,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(name.isNotBlank()) { "客户名不能为空" }
        require(storeId.isNotBlank()) { "store_id 不能为空" }
        require(phone == null || phone.isNotBlank()) { "电话不能为空白字符串" }
        require(alias == null || alias.isNotBlank()) { "别名不能为空白字符串" }
    }
}
