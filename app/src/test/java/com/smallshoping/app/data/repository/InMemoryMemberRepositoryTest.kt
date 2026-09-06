package com.smallshoping.app.data.repository

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.MemberStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 020：会员目录内存实现语义基线。 */
class InMemoryMemberRepositoryTest {

    private val repo = InMemoryMemberRepository()

    private fun seed(name: String = "张姐"): Member {
        val member = Member(
            id = "M-1", storeId = "STORE-1", name = name,
            normalizedName = normalize(name), alias = "张女士", phone = "13800000000"
        )
        repo.saveMember(member)
        return member
    }

    @Test
    fun `保存与查找：按 id 与归一化名称`() {
        val member = seed()
        assertEquals(member, repo.findMemberById("M-1"))
        assertEquals(member, repo.findByNormalizedName(normalize("张姐")))
        assertNull(repo.findMemberById("M-404"))
    }

    @Test
    fun `searchMembers：名称、别名、电话均可命中（归一化比较）`() {
        seed()
        assertEquals(1, repo.searchMembers(normalize("张姐")).size)
        assertEquals(1, repo.searchMembers(normalize("张女士")).size)
        assertEquals(1, repo.searchMembers("13800000000").size)
        assertTrue(repo.searchMembers(normalize("李姐")).isEmpty())
    }

    @Test
    fun `allMembers：返回全部会员，保持保存顺序`() {
        seed()
        repo.saveMember(
            Member("M-2", "STORE-1", "王叔", normalize("王叔"))
        )
        val all = repo.allMembers()
        assertEquals(2, all.size)
        assertEquals("M-1", all[0].id)
        assertEquals(MemberStatus.ACTIVE, all[0].status)
    }
}
