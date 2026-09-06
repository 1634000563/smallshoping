package com.smallshoping.app.ai.entityresolution

import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.data.repository.InMemoryMemberRepository
import com.smallshoping.app.domain.member.Member
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 022：会员实体解析打分与消歧。 */
class MemberResolverTest {

    private val members = InMemoryMemberRepository()
    private val resolver = MemberResolver(members)

    private fun seed(
        id: String,
        name: String,
        alias: String? = null,
        phone: String? = null
    ) {
        members.saveMember(
            Member(id = id, storeId = "STORE-1", name = name, normalizedName = normalize(name),
                alias = alias, phone = phone)
        )
    }

    @Test
    fun `名称精确命中`() {
        seed("M-1", "张姐")
        val r = resolver.resolve("张姐")
        assertTrue(r is Resolution.Resolved)
        val resolved = r as Resolution.Resolved<Member>
        assertEquals("M-1", resolved.value.id)
        assertEquals(100, resolved.score)
    }

    @Test
    fun `别名与电话精确命中`() {
        seed("M-1", "张桂芳", alias = "张姐", phone = "13800000000")
        assertEquals("M-1", (resolver.resolve("张姐") as Resolution.Resolved<Member>).value.id)
        assertEquals("M-1", (resolver.resolve("13800000000") as Resolution.Resolved<Member>).value.id)
    }

    @Test
    fun `多候选返回歧义不猜测`() {
        seed("M-1", "张姐")
        seed("M-2", "张姐王")
        val r = resolver.resolve("张")
        assertTrue(r is Resolution.Ambiguous)
        val candidates = (r as Resolution.Ambiguous<Member>).candidates
        assertEquals(2, candidates.size)
        assertEquals("M-1", candidates[0].value.id) // 前缀命中排序在前
    }

    @Test
    fun `无候选返回 NotFound`() {
        seed("M-1", "张姐")
        assertTrue(resolver.resolve("李姐") is Resolution.NotFound)
        assertTrue(resolver.resolve("  ") is Resolution.NotFound)
    }

    @Test
    fun `模糊命中：名称包含与别名包含打分`() {
        seed("M-1", "土豆批发张姐")
        assertEquals(40, (resolver.resolve("批发") as Resolution.Resolved<Member>).score)
        // 别名包含 20 分：名称不含「豆豆」时才能走到别名规则
        seed("M-2", "张姐", alias = "小豆豆")
        assertEquals(20, (resolver.resolve("豆豆") as Resolution.Resolved<Member>).score)
    }
}
