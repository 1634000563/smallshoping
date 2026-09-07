package com.smallshoping.app.data.repository

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.MemberRepository

/**
 * 内存会员目录实现：线程安全。
 *
 * 语义基线同 [com.smallshoping.app.data.ledger.InMemoryLedger]：
 * 真实持久化实现（Room/SQLite）须通过同一组测试。
 */
class InMemoryMemberRepository(
    /** 写穿钩子（Task 059 SQLite 持久化）；null 时纯内存。 */
    private val persist: com.smallshoping.app.data.sqlite.MemberPersistence? = null
) : MemberRepository {

    private val lock = Any()
    private val byId = LinkedHashMap<String, Member>()
    private val byName = HashMap<String, Member>()

    override fun saveMember(member: Member) = synchronized(lock) {
        persist?.onMember(member)
        byId[member.id] = member
        byName[member.normalizedName] = member
    }

    override fun findMemberById(id: String): Member? = synchronized(lock) {
        byId[id]
    }

    override fun allMembers(): List<Member> = synchronized(lock) {
        byId.values.toList()
    }

    override fun findByNormalizedName(normalizedName: String): Member? = synchronized(lock) {
        byName[normalizedName]
    }

    override fun searchMembers(normalizedQuery: String): List<Member> = synchronized(lock) {
        byId.values.filter { member ->
            member.normalizedName == normalizedQuery ||
                (member.alias != null && normalize(member.alias) == normalizedQuery) ||
                member.phone == normalizedQuery
        }
    }
}
