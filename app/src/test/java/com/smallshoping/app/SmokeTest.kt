package com.smallshoping.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** Task 002 最小冒烟测试：验证 JVM 单元测试链路可用。 */
class SmokeTest {

    @Test
    fun `工程单元测试链路可用`() {
        assertEquals(4, 2 + 2)
    }
}
