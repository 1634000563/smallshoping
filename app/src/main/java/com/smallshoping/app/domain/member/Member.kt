package com.smallshoping.app.domain.member

/** 会员状态（spec 03 §4 member.status 最小子集）。 */
enum class MemberStatus { ACTIVE, DISABLED }

/**
 * 会员账户（spec 03 §4 member 表）：
 * id, name, phone, alias, status, created_at, updated_at。
 *
 * 余额不是会员本体字段——必须由 member_ledger 流水派生（spec 04 §6），
 * 任何页面/UseCase 都不得直接读改写一个「余额字段」。
 */
data class Member(
    val id: String,
    val storeId: String,
    val name: String,
    val normalizedName: String,
    val phone: String? = null,
    val alias: String? = null,
    val status: MemberStatus = MemberStatus.ACTIVE,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(name.isNotBlank()) { "会员名不能为空" }
        require(storeId.isNotBlank()) { "store_id 不能为空" }
        require(phone == null || phone.isNotBlank()) { "电话不能为空白字符串" }
        require(alias == null || alias.isNotBlank()) { "别名不能为空白字符串" }
    }
}
