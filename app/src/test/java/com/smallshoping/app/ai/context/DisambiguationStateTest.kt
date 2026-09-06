package com.smallshoping.app.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Task 025：追问选择解析（候选名精确 / 第X个）。 */
class DisambiguationStateTest {

    private fun question(candidates: List<Pair<String, String>>) = PendingQuestion(
        toolName = "recharge_member",
        entities = mapOf("member" to "小张", "amount" to "20000"),
        ambiguousKey = "member",
        candidates = candidates.map { CandidateRef(it.first, it.second) },
        expiresAtMillis = Long.MAX_VALUE
    )

    @Test
    fun `候选名精确命中（归一化比较）`() {
        val q = question(listOf("M-1" to "小张姐", "M-2" to "小张哥"))
        assertEquals("M-1", q.choose("小张姐")?.id)
        assertEquals("M-1", q.choose(" 小张姐 ")?.id)
        assertEquals("M-2", q.choose("小张哥")?.id)
    }

    @Test
    fun `第X个与数字指认`() {
        val q = question(listOf("M-1" to "小张姐", "M-2" to "小张哥", "M-3" to "小张嫂"))
        assertEquals("M-1", q.choose("第一个")?.id)
        assertEquals("M-2", q.choose("第二个")?.id)
        assertEquals("M-2", q.choose("2")?.id)
        assertEquals("M-3", q.choose("三")?.id)
        assertEquals("M-3", q.choose("第3名")?.id)
    }

    @Test
    fun `越界与非法输入：不猜测`() {
        val q = question(listOf("M-1" to "小张姐", "M-2" to "小张哥"))
        assertNull(q.choose("第五个"))
        assertNull(q.choose("9"))
        assertNull(q.choose("随便"))
        assertNull(q.choose(""))
        assertNull(q.choose("   "))
    }
}
