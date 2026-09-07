package com.smallshoping.app.app

import android.app.Application
import com.smallshoping.app.app.di.CompositionRoot

/**
 * 应用级组合根（Task 059 真机验收修复）：
 * 全进程共享同一个 [CompositionRoot]——主界面与查账页看到同一份账务事实。
 * 此前各 Activity 各自 new 一个根，查账页永远读到空数据。
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        // Task 059 SQLite 持久化：写穿落盘 + 启动水合（重启不丢账）
        val db = com.smallshoping.app.data.sqlite.ShopDatabase(this)
        root = CompositionRoot(persistence = com.smallshoping.app.data.sqlite.ShopPersistence(db))
    }

    companion object {
        lateinit var root: CompositionRoot
            private set
    }
}
