package com.smallshoping.app.domain

/**
 * Domain 层统一入口契约（Task 003 边界骨架）。
 *
 * 所有业务写操作必须经由具体 UseCase 实现进入 Domain：
 * AI 只能通过 Tool 调用 UseCase，UI 只能通过 ViewModel 调用 UseCase，
 * 任何一层不得绕过 Domain 直接修改数据事实。
 */
fun interface UseCase<in I, out O> {

    operator fun invoke(input: I): O
}
