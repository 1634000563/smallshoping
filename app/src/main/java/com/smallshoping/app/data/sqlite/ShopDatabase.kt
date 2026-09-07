package com.smallshoping.app.data.sqlite

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * V1 SQLite 事实库（Task 059 持久化，数据宪法：SQLite/Ledger 是本地事实源）。
 *
 * 语义基线：所有写操作与 InMemory 实现保持同一语义（幂等、事务、可重建）——
 * 内存实现仍是运行时主存，SQLite 是写穿（write-through）事实库与启动水合源。
 * 迁移规则：表结构变更必须提升 [SCHEMA_VERSION] 并在 [onUpgrade] 内做确定性迁移。
 */
class ShopDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "shop.db", null, SCHEMA_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        // 商品与目录
        db.execSQL(
            """CREATE TABLE products(
                id TEXT PRIMARY KEY, store_id TEXT NOT NULL, name TEXT NOT NULL,
                normalized_name TEXT NOT NULL, sale_unit TEXT NOT NULL,
                purchase_unit TEXT NOT NULL, sale_price_minor INTEGER NOT NULL,
                cost_price_minor INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE product_aliases(
                id TEXT PRIMARY KEY, product_id TEXT NOT NULL, alias TEXT NOT NULL,
                normalized_alias TEXT NOT NULL, source TEXT NOT NULL, weight INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE product_attributes(
                id TEXT PRIMARY KEY, product_id TEXT NOT NULL,
                attr_key TEXT NOT NULL, attr_value TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE price_history(
                id TEXT PRIMARY KEY, product_id TEXT NOT NULL,
                price_type TEXT NOT NULL, old_minor INTEGER, new_minor INTEGER NOT NULL,
                unit TEXT NOT NULL, source TEXT NOT NULL, changed_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE product_barcodes(
                id TEXT PRIMARY KEY, product_id TEXT NOT NULL, barcode TEXT NOT NULL,
                barcode_type TEXT NOT NULL, is_primary INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE unit_conversions(
                id TEXT PRIMARY KEY, product_id TEXT NOT NULL,
                from_unit TEXT NOT NULL, to_unit TEXT NOT NULL,
                ratio_numerator INTEGER NOT NULL, ratio_denominator INTEGER NOT NULL)"""
        )
        // 三本账流水（幂等键唯一 = 崩溃恢复去重）
        db.execSQL(
            """CREATE TABLE ledger_entries(
                id TEXT PRIMARY KEY, scope_type TEXT NOT NULL, scope_id TEXT NOT NULL,
                movement_type TEXT NOT NULL, delta INTEGER NOT NULL,
                reference_type TEXT, reference_id TEXT,
                idem_key TEXT NOT NULL, note TEXT, created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            "CREATE UNIQUE INDEX idx_ledger_idem ON ledger_entries(scope_type, scope_id, idem_key)"
        )
        // 销售单与明细
        db.execSQL(
            """CREATE TABLE sales(
                id TEXT PRIMARY KEY, store_id TEXT NOT NULL, status TEXT NOT NULL,
                payment_method TEXT, total_minor INTEGER NOT NULL,
                checkout_idem_key TEXT, customer_id TEXT, member_id TEXT,
                created_at INTEGER NOT NULL, completed_at INTEGER)"""
        )
        db.execSQL(
            """CREATE TABLE sale_items(
                id TEXT PRIMARY KEY, sale_id TEXT NOT NULL, idx INTEGER NOT NULL,
                product_id TEXT NOT NULL, product_name TEXT NOT NULL,
                quantity_scaled INTEGER NOT NULL, quantity_unit TEXT NOT NULL,
                unit_price_minor INTEGER NOT NULL, subtotal_minor INTEGER NOT NULL)"""
        )
        // 支付记录
        db.execSQL(
            """CREATE TABLE payments(
                id TEXT PRIMARY KEY, sale_id TEXT NOT NULL, method TEXT NOT NULL,
                status TEXT NOT NULL, amount_minor INTEGER NOT NULL,
                external_reference TEXT, idem_key TEXT NOT NULL,
                created_at INTEGER NOT NULL, confirmed_at INTEGER)"""
        )
        // 采购单 / 损耗单
        db.execSQL(
            """CREATE TABLE purchases(
                id TEXT PRIMARY KEY, store_id TEXT NOT NULL, product_id TEXT NOT NULL,
                quantity_scaled INTEGER NOT NULL, quantity_unit TEXT NOT NULL,
                unit_cost_minor INTEGER, idem_key TEXT NOT NULL, created_at INTEGER NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE losses(
                id TEXT PRIMARY KEY, product_id TEXT NOT NULL,
                quantity_scaled INTEGER NOT NULL, quantity_unit TEXT NOT NULL,
                reason TEXT NOT NULL, cost_minor INTEGER NOT NULL,
                stock_entry_id TEXT NOT NULL, created_at INTEGER NOT NULL)"""
        )
        // 会员 / 客户
        db.execSQL(
            """CREATE TABLE members(
                id TEXT PRIMARY KEY, store_id TEXT NOT NULL, name TEXT NOT NULL,
                normalized_name TEXT NOT NULL)"""
        )
        db.execSQL(
            """CREATE TABLE customers(
                id TEXT PRIMARY KEY, store_id TEXT NOT NULL, name TEXT NOT NULL,
                normalized_name TEXT NOT NULL)"""
        )
        // 日结快照
        db.execSQL(
            """CREATE TABLE day_closes(
                id TEXT PRIMARY KEY, business_date TEXT NOT NULL UNIQUE,
                opened_at INTEGER NOT NULL, closed_at INTEGER NOT NULL,
                cash_expected INTEGER NOT NULL, cash_actual INTEGER NOT NULL,
                variance INTEGER NOT NULL, status TEXT NOT NULL, note TEXT NOT NULL)"""
        )
        // 命令日志（崩溃恢复重放）
        db.execSQL(
            """CREATE TABLE command_journal(
                id TEXT PRIMARY KEY, tool_name TEXT NOT NULL, entities_json TEXT NOT NULL,
                model_id TEXT NOT NULL, prompt_version TEXT NOT NULL,
                tool_schema_version TEXT NOT NULL, app_version TEXT NOT NULL,
                created_at INTEGER NOT NULL)"""
        )
        // 会话上下文（当前草稿单等，重启续营业）
        db.execSQL(
            """CREATE TABLE session_contexts(
                device_id TEXT PRIMARY KEY, json TEXT NOT NULL)"""
        )
        // 老板习惯记忆
        db.execSQL(
            """CREATE TABLE memory_facts(
                id TEXT PRIMARY KEY, scope_type TEXT NOT NULL, scope_id TEXT NOT NULL,
                fact_type TEXT NOT NULL, fact_key TEXT NOT NULL, value_json TEXT NOT NULL,
                confidence INTEGER NOT NULL, source TEXT NOT NULL,
                last_confirmed_at INTEGER, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, active INTEGER NOT NULL)"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // V1 首次发布无历史版本；后续迁移必须确定性（重建校验 + 备份恢复语义）
        // 预留：按版本逐级迁移
    }

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
