package com.smallshoping.app.feature.report

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.smallshoping.app.app.di.CompositionRoot

/**
 * 兜底查询页（Task 043）：历史/商品/报表，仅只读。
 *
 * Route Guard：本页只有查询按钮，没有任何写操作入口——
 * 传统页面仅作兜底，不得演变成传统 POS 主流程。
 */
class FallbackActivity : Activity() {

    private val root = CompositionRoot()
    private val viewModel = FallbackViewModel(root)
    private lateinit var resultText: TextView
    private lateinit var queryInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        show(viewModel.todaySales())
    }

    private fun buildLayout(): LinearLayout {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        layout.addView(
            TextView(this).apply {
                text = "查账（只读兜底）"
                textSize = 18f
                setTextColor(Color.DKGRAY)
            },
            wrapWidth()
        )

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row1.addView(button("今天销售") { show(viewModel.todaySales()) }, wrap())
        row1.addView(button("商品库存") { show(viewModel.productList()) }, wrap())
        row1.addView(button("会员客户") { show(viewModel.balances()) }, wrap())
        layout.addView(row1, wrapWidth())

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        queryInput = EditText(this).apply {
            hint = "查客户历史/价格历史：输入名字后点按钮"
            textSize = 15f
        }
        row2.addView(queryInput, LinearLayout.LayoutParams(0, wrapHeight(), 1f))
        row2.addView(
            button("客户历史") { show(viewModel.customerHistory(queryInput.text.toString())) },
            wrap()
        )
        row2.addView(
            button("价格历史") { show(viewModel.priceHistory(queryInput.text.toString())) },
            wrap()
        )
        layout.addView(row2, wrapWidth())

        resultText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        val scroll = ScrollView(this).apply { addView(resultText, wrapWidth()) }
        layout.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        layout.addView(
            Button(this).apply {
                text = "返回营业"
                setOnClickListener { finish() }
            },
            wrapWidth()
        )
        return layout
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun show(text: String) {
        resultText.text = text
    }

    private fun wrapWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, wrapHeight()
    )

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, wrapHeight()
    )

    private fun wrapHeight() = ViewGroup.LayoutParams.WRAP_CONTENT
}
