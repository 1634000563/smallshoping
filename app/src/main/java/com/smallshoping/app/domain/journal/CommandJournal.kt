package com.smallshoping.app.domain.journal

import java.util.UUID

/**
 * 一条写命令日志（崩溃恢复依据，spec 04 §4）。
 *
 * 幂等键不显式存储——所有 Handler 的幂等键都由入参确定性生成
 * （如 recharge:{memberId}:{fen}），重放时以相同入参重执行，
 * 账本幂等去重保证不重复记账。
 */
data class CommandRecord(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    /** 入参编码（key=value&key=value），与 Handler 幂等键生成输入一致。 */
    val entitiesJson: String,
    val createdAtMillis: Long = System.currentTimeMillis()
) {

    init {
        require(toolName.isNotBlank()) { "toolName 不能为空" }
    }
}

/**
 * 命令日志端口：只追加；持久化实现（Task 046 备份/恢复）须保持同语义。
 */
interface CommandJournal {

    fun append(record: CommandRecord)

    /** 全部命令（按追加顺序）。 */
    fun all(): List<CommandRecord>

    /** 已重放成功后清空（V1 内存版）。 */
    fun clear()
}
