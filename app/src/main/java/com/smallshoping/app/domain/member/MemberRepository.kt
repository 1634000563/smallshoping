package com.smallshoping.app.domain.member

/**
 * 会员目录端口（Domain 侧契约）。
 *
 * 查询一律使用归一化值；Data 层实现负责持久化，
 * AI 与 UI 不得直接持有本端口之外的数据访问能力。
 */
interface MemberRepository {

    fun saveMember(member: Member)

    fun findMemberById(id: String): Member?

    /** 全部会员（Entity Resolution 候选扫描用）。 */
    fun allMembers(): List<Member>

    /** 按归一化会员名精确查找。 */
    fun findByNormalizedName(normalizedName: String): Member?

    /** 归一化查询：匹配归一化名称、归一化别名或电话（Task 022 消歧候选用）。 */
    fun searchMembers(normalizedQuery: String): List<Member>
}
