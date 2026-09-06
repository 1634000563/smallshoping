package com.smallshoping.app.feature.home

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.device.voice.AndroidSpeechRecognizerProvider
import com.smallshoping.app.device.voice.SpeechResult

/**
 * 极简主界面（Task 041）：语音/扫码（文本）/当前任务。
 *
 * - 无传统菜单（Route Guard）；老板只会看到：回复区、当前任务、
 *   一个输入框、语音按钮、确认/取消按钮；
 * - 所有输入汇入 [HomeViewModel.handleInput]（唯一链路 UI→AI→Tool→Domain）；
 * - 语音失败自动降级提示用键盘输入（离线宪法 #6）。
 */
class MainActivity : Activity() {

    private val root = CompositionRoot()
    private lateinit var viewModel: HomeViewModel
    private lateinit var replyText: TextView
    private lateinit var taskText: TextView
    private lateinit var input: EditText
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button
    private val speech = lazy { AndroidSpeechRecognizerProvider(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = HomeViewModel(root)
        setContentView(buildLayout())
    }

    private fun buildLayout(): LinearLayout {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.WHITE)
        }

        // 当前任务区
        taskText = TextView(this).apply {
            textSize = 16f
            setTextColor(Color.DKGRAY)
            text = "当前没有待结账的单子"
        }
        rootLayout.addView(taskText, matchWidth())

        // 回复区（可滚动）
        replyText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            text = "说什么：卖两斤土豆 / 进100斤土豆成本2块8 / 结账 / 今天卖了多少钱"
        }
        val scroll = ScrollView(this).apply { addView(replyText, matchWidth()) }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 确认/取消（有待确认操作时使用）
        val confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        confirmButton = Button(this).apply {
            text = "确认"
            setOnClickListener { render(viewModel.confirm(approved = true)) }
        }
        cancelButton = Button(this).apply {
            text = "取消"
            setOnClickListener { render(viewModel.confirm(approved = false)) }
        }
        confirmRow.addView(confirmButton, wrap())
        confirmRow.addView(cancelButton, wrap())
        rootLayout.addView(confirmRow, matchWidth())

        // 输入行：输入框 + 发送 + 语音
        val inputRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        input = EditText(this).apply {
            hint = "打字或按语音说（条码直接输入数字）"
            textSize = 16f
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, wrapHeight(), 1f))
        inputRow.addView(
            Button(this).apply {
                text = "发送"
                setOnClickListener {
                    render(viewModel.handleInput(input.text.toString()))
                    input.text.clear()
                }
            },
            wrap()
        )
        inputRow.addView(
            Button(this).apply {
                text = "🎤"
                setOnClickListener { startVoice() }
            },
            wrap()
        )
        rootLayout.addView(inputRow, matchWidth())
        return rootLayout
    }

    private fun startVoice() {
        speech.value.listen { result ->
            runOnUiThread {
                when (result) {
                    is SpeechResult.Transcript -> render(viewModel.handleInput(result.text))
                    is SpeechResult.NoMatch -> render(HomeUiState("没听清，请再说一次"))
                    is SpeechResult.Error -> render(
                        HomeUiState("${result.message}（可用键盘继续营业）")
                    )
                }
            }
        }
    }

    private fun render(state: HomeUiState) {
        replyText.text = state.reply
        taskText.text = state.currentTask
        val pending = state.pendingConfirmId != null
        confirmButton.isEnabled = pending
        cancelButton.isEnabled = pending
        if (pending) {
            Toast.makeText(this, "请按「确认」执行或「取消」", Toast.LENGTH_SHORT).show()
        }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, wrapHeight()
    )

    private fun wrap() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, wrapHeight()
    )

    private fun wrapHeight() = ViewGroup.LayoutParams.WRAP_CONTENT

    override fun onDestroy() {
        super.onDestroy()
        if (speech.isInitialized()) speech.value.cancel()
    }
}
