package com.smallshoping.app.feature.home

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.TextView

/**
 * Task 002 工程验证页：最小可安装 APK 的占位界面。
 * 后续由 Task 041 极简主界面（语音/扫码/当前任务）替换，此处不建任何菜单。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "小店操作系统 V1\n工程构建正常"
                textSize = 22f
                setTextColor(Color.BLACK)
                setPadding(48, 48, 48, 48)
            }
        )
    }
}
