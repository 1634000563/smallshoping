package com.smallshoping.app.domain.backup

import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductRepository
import com.smallshoping.app.domain.customer.Customer
import com.smallshoping.app.domain.customer.CustomerRepository
import com.smallshoping.app.domain.journal.CommandJournal
import com.smallshoping.app.domain.journal.CommandRecord
import com.smallshoping.app.domain.ledger.IdempotencyKey
import com.smallshoping.app.domain.ledger.Ledger
import com.smallshoping.app.domain.ledger.LedgerEntry
import com.smallshoping.app.domain.ledger.LedgerScope
import com.smallshoping.app.domain.ledger.LedgerScopeType
import com.smallshoping.app.domain.ledger.MovementType
import com.smallshoping.app.domain.member.Member
import com.smallshoping.app.domain.member.MemberRepository
import java.security.MessageDigest

/**
 * 备份快照（spec 19 §5）：必须包含 schema_version、store_id、export_time、checksum。
 */
data class BackupSnapshot(
    val schemaVersion: Int,
    val storeId: String,
    val exportTimeMillis: Long,
    val checksum: String,
    /** 行式编码的业务事实（V1 无外部 JSON 依赖，每行一条事实）。 */
    val payload: String
) {
    init {
        require(schemaVersion > 0) { "schema_version 必须为正" }
        require(storeId.isNotBlank()) { "store_id 不能为空" }
        require(checksum.isNotBlank()) { "checksum 不能为空" }
    }
}

/**
 * 备份/恢复（Task 046，V1 内存版）：
 *
 * V1 备份范围 = 账务事实 + 目录 + 命令日志——
 * 库存/余额/欠款全部由 Ledger 流水重建（数据宪法 #5），
 * 商品/会员/客户目录与命令日志保证经营连续性；
 * 销售单/损耗单/价格历史/记忆的完整导出由 Task 049 补。
 *
 * 恢复前先校验 checksum 与 schema_version（spec 19 §5），
 * 恢复按幂等键追加流水，重复恢复不重复记账。
 */
class BackupService(
    private val ledger: Ledger,
    private val products: ProductRepository,
    private val members: MemberRepository,
    private val customers: CustomerRepository,
    private val journal: CommandJournal,
    private val storeId: String
) {

    fun export(): BackupSnapshot {
        val lines = ArrayList<String>()
        // 商品
        for (p in products.allProducts()) {
            lines.add(
                "P|${p.id}|${p.name}|${p.normalizedName}|${p.saleUnit.code}|${p.purchaseUnit.code}|" +
                    "${p.currentSalePrice.minor}|${p.currentCostPrice?.minor ?: ""}"
            )
        }
        // 流水（全部范围）
        for (scope in ledger.allScopes().sortedBy { "${it.type}${it.scopeId}" }) {
            for (e in ledger.entries(scope)) {
                lines.add(
                    "L|${scope.type}|${scope.scopeId}|${e.movementType}|${e.delta}|" +
                        "${e.idempotencyKey.value}|${e.note ?: ""}"
                )
            }
        }
        // 会员 / 客户
        for (m in members.allMembers()) lines.add("M|${m.id}|${m.name}|${m.normalizedName}")
        for (c in customers.allCustomers()) lines.add("C|${c.id}|${c.name}|${c.normalizedName}")
        // 命令日志
        for (r in journal.all()) lines.add("J|${r.toolName}|${r.entitiesJson}")

        val payload = lines.joinToString("\n")
        val checksum = sha256("$SCHEMA_VERSION|$storeId|$payload")
        return BackupSnapshot(
            schemaVersion = SCHEMA_VERSION,
            storeId = storeId,
            exportTimeMillis = System.currentTimeMillis(),
            checksum = checksum,
            payload = payload
        )
    }

    /** 校验：checksum 与 schema_version（恢复前必须先通过，spec 19 §5）。 */
    fun validate(snapshot: BackupSnapshot): String? {
        if (snapshot.schemaVersion != SCHEMA_VERSION) {
            return "schema 版本不支持：${snapshot.schemaVersion}（当前 $SCHEMA_VERSION）"
        }
        val expected = sha256("${snapshot.schemaVersion}|${snapshot.storeId}|${snapshot.payload}")
        if (snapshot.checksum != expected) {
            return "checksum 校验失败，备份可能已损坏"
        }
        if (snapshot.storeId != storeId) {
            return "store_id 不匹配：备份 ${snapshot.storeId}，当前 $storeId"
        }
        return null
    }

    /** 恢复：校验通过后逐行重建（流水按幂等键追加，重复恢复不重复记账）。 */
    fun restore(snapshot: BackupSnapshot): Boolean {
        validate(snapshot)?.let { return false }
        for (line in snapshot.payload.lines()) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            when (parts[0]) {
                "P" -> {
                    val cost = parts[7].takeIf { it.isNotBlank() }?.let { Money(it.toLong()) }
                    products.saveProduct(
                        Product(
                            id = parts[1], storeId = snapshot.storeId, name = parts[2],
                            normalizedName = parts[3],
                            saleUnit = unitByCode(parts[4]), purchaseUnit = unitByCode(parts[5]),
                            currentSalePrice = Money(parts[6].toLong()), currentCostPrice = cost
                        )
                    )
                }

                "L" -> {
                    val scope = LedgerScope(LedgerScopeType.valueOf(parts[1]), parts[2])
                    ledger.append(
                        LedgerEntry(
                            scope = scope,
                            movementType = MovementType.valueOf(parts[3]),
                            delta = parts[4].toLong(),
                            idempotencyKey = IdempotencyKey(parts[5]),
                            note = parts[6].takeIf { it.isNotBlank() }
                        )
                    )
                }

                "M" -> members.saveMember(
                    Member(
                        id = parts[1], storeId = snapshot.storeId, name = parts[2],
                        normalizedName = parts[3]
                    )
                )

                "C" -> customers.saveCustomer(
                    Customer(
                        id = parts[1], storeId = snapshot.storeId, name = parts[2],
                        normalizedName = parts[3]
                    )
                )

                "J" -> journal.append(CommandRecord(toolName = parts[1], entitiesJson = parts[2]))
            }
        }
        return true
    }

    private fun unitByCode(code: String): Unit = when (code) {
        "G" -> Unit.GRAM
        "KG" -> Unit.KILOGRAM
        "JIN" -> Unit.JIN
        "PCS" -> Unit.PIECE
        "BOX" -> Unit.BOX
        "M" -> Unit.METER
        else -> throw IllegalArgumentException("未知单位：$code")
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** V1 备份 schema 版本；结构变更必须递增（spec 19 恢复前校验）。 */
        const val SCHEMA_VERSION = 1
    }
}
