package com.smallshoping.app.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.device.scanner.BarcodeScanner
import com.smallshoping.app.device.scanner.MlKitBarcodeScanner
import com.smallshoping.app.device.scanner.ScanResult

/**
 * 极简主界面（Task 041/042，Task 059 微信式重构）：
 * 上方整块聊天窗口（消息气泡累积，老板的话在右、AI 回复在左），
 * 底部微信式输入栏——点输入框键盘弹起、输入栏紧贴键盘上沿；
 * 无内容显示「＋」（展开扫/手动加/人工结账/查账面板），有内容显示「发送」；
 * 确认卡片嵌入聊天流（黄色气泡 + 卡片内确认/取消）。
 *
 * - 无传统菜单（Route Guard）；所有输入汇入 [HomeViewModel.handleInput]
 *   （唯一链路 UI→AI→Tool→Domain）；
 * - 人工接管：AI 不可用时「手动加」「人工结账」直接走 Domain（Gate A 同一事实）；
 * - 语音输入由系统键盘提供（ADR-017）：键盘自带语音键，应用不内置 ASR；
 * - 扫码失败自动降级提示用键盘输入（离线宪法 #6）。
 */
class MainActivity : ComponentActivity() {

    /** 全进程共享组合根（App 创建）：主界面与查账页同一份账务事实。 */
    private val root = com.smallshoping.app.app.App.root
    private lateinit var viewModel: HomeViewModel
    private lateinit var taskText: TextView
    private lateinit var chatContainer: LinearLayout
    private lateinit var confirmRow: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendButton: Button
    private lateinit var plusButton: Button
    private lateinit var plusPanel: LinearLayout
    private lateinit var previewView: PreviewView
    private var barcodeScanner: BarcodeScanner? = null

    /** Task 052：权限走 registerForActivityResult（onRequestPermissionsResult 已弃用）。 */
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginScan()
        } else {
            render(HomeUiState(UiKind.ERROR, "没有相机权限，请用键盘输入条码"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = HomeViewModel(root)
        setContentView(buildLayout())
        // Task 052：扫码中按返回只退出扫码，不退出应用（真机行为）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (previewView.visibility == android.view.View.VISIBLE) {
                    barcodeScanner?.cancel()
                    previewView.visibility = android.view.View.GONE
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    private fun buildLayout(): LinearLayout {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            // 键盘弹起时输入栏紧贴键盘上沿（WindowInsets 方案，全设备可靠）
            ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
                val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
                v.setPadding(0, 0, 0, ime.bottom)
                insets
            }
        }

        // ── 聊天窗口（占满上方全部空间，消息气泡累积）──
        taskText = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(32, 12, 32, 4)
            text = "当前没有待结账的单子"
        }
        chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(taskText, matchWidth())
        }
        // 确认卡片（嵌入聊天流，待确认时显示在回复气泡下方）
        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
        }
        confirmRow.addView(
            Button(this).apply {
                text = "确认"
                setOnClickListener { render(viewModel.confirm(approved = true)) }
            },
            wrap()
        )
        confirmRow.addView(
            Button(this).apply {
                text = "取消"
                setOnClickListener { render(viewModel.confirm(approved = false)) }
            },
            wrap()
        )
        chatContainer.addView(confirmRow, matchWidth())
        scroll = ScrollView(this).apply {
            addView(chatContainer, matchWidth())
            isFillViewport = true
        }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 欢迎语（左侧气泡）
        appendBubble("说什么：卖两斤土豆 / 进100斤土豆成本2块8 / 结账 / 今天卖了多少钱", mine = false, kind = UiKind.NORMAL)

        // 扫码取景框（扫码时临时显示，Task 052）
        previewView = PreviewView(this).apply { visibility = android.view.View.GONE }
        rootLayout.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 320
        ))

        // ── ＋功能面板（点「＋」展开/收起，所有功能按钮集中在此）──
        plusPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(16, 8, 16, 8)
            setBackgroundColor(Color.rgb(247, 247, 247))
        }
        val plusRow1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        plusRow1.addView(panelButton("扫") { startScan() }, panelWeight())
        plusRow1.addView(panelButton("手动加（如：土豆 2斤）") {
            val raw = input.text.toString().trim()
            val parts = raw.split(Regex("\\s+"), limit = 2)
            if (parts.size == 2) {
                appendBubble(raw, mine = true, kind = UiKind.NORMAL)
                render(viewModel.manualAddItem(parts[0], parts[1]))
            } else {
                render(HomeUiState(UiKind.ERROR, "手动加格式：商品名 数量（如 土豆 2斤）"))
            }
            input.text.clear()
        }, panelWeight())
        val plusRow2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        plusRow2.addView(panelButton("人工结账") {
            appendBubble("人工结账", mine = true, kind = UiKind.NORMAL)
            render(viewModel.manualCheckout())
        }, panelWeight())
        plusRow2.addView(panelButton("查账") {
            startActivity(
                android.content.Intent(this@MainActivity, com.smallshoping.app.feature.report.FallbackActivity::class.java)
            )
        }, panelWeight())
        plusPanel.addView(plusRow1, matchWidth())
        plusPanel.addView(plusRow2, matchWidth())
        rootLayout.addView(plusPanel, matchWidth())

        // ── 底部输入栏（微信式）：[输入框][发送/＋]；键盘弹起输入栏紧贴键盘 ──
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 16)
        }
        input = EditText(this).apply {
            hint = "打字说，或按住键盘的语音键说（条码直接输入数字）"
            textSize = 16f
            maxLines = 3
            setPadding(40, 20, 40, 20)
            background = GradientDrawable().apply {
                cornerRadius = 56f
                setColor(Color.rgb(245, 245, 245))
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) plusPanel.visibility = android.view.View.GONE
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    updateSendPlusVisibility()
                }
            })
        }
        inputBar.addView(input, LinearLayout.LayoutParams(0, wrapHeight(), 1f).apply {
            marginStart = 16
            marginEnd = 16
        })
        // 微信式：输入框有内容显示「发送」，无内容显示「＋」（同位置互换）
        sendButton = Button(this).apply {
            text = "发送"
            setBackgroundColor(Color.rgb(7, 193, 96)) // 微信绿
            setTextColor(Color.WHITE)
            setOnClickListener {
                val text = input.text.toString()
                if (text.isBlank()) return@setOnClickListener
                appendBubble(text, mine = true, kind = UiKind.NORMAL)
                render(viewModel.handleInput(text))
                input.text.clear()
            }
        }
        plusButton = Button(this).apply {
            text = "＋"
            textSize = 22f
            setOnClickListener {
                // 展开面板时收起键盘，让按钮完整可见
                input.clearFocus()
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(input.windowToken, 0)
                plusPanel.visibility = if (plusPanel.visibility == android.view.View.VISIBLE) {
                    android.view.View.GONE
                } else {
                    android.view.View.VISIBLE
                }
            }
        }
        inputBar.addView(sendButton, wrap())
        inputBar.addView(plusButton, wrap())
        rootLayout.addView(inputBar, matchWidth())
        updateSendPlusVisibility()
        return rootLayout
    }

    /** 微信式按钮互换：有内容 → 发送；无内容 → ＋。 */
    private fun updateSendPlusVisibility() {
        val hasText = input.text.isNotEmpty()
        sendButton.visibility = if (hasText) android.view.View.VISIBLE else android.view.View.GONE
        plusButton.visibility = if (hasText) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** ＋面板按钮：大按钮、微信风灰底。 */
    private fun panelButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 16f
            setBackgroundColor(Color.rgb(230, 230, 230))
            setOnClickListener { onClick() }
        }

    private fun panelWeight() = LinearLayout.LayoutParams(0, 160, 1f).apply {
        marginStart = 8
        marginEnd = 8
    }

    /** 追加一条聊天气泡：老板的话右侧绿，回复左侧（确认黄、异常红字）。 */
    private fun appendBubble(text: String, mine: Boolean, kind: UiKind) {
        if (text.isBlank()) return
        val bubble = TextView(this).apply {
            this.text = text
            textSize = 18f
            setPadding(36, 24, 36, 24)
            background = GradientDrawable().apply {
                cornerRadius = 28f
                when {
                    mine -> setColor(Color.rgb(149, 236, 105)) // 微信绿气泡
                    kind == UiKind.CONFIRM -> setColor(Color.rgb(255, 244, 150)) // 确认黄卡片
                    else -> setColor(Color.rgb(245, 245, 245)) // 灰回复
                }
            }
            setTextColor(if (!mine && kind == UiKind.ERROR) Color.RED else Color.BLACK)
            if (mine) typeface = Typeface.DEFAULT else typeface = Typeface.DEFAULT_BOLD
        }
        // 确认卡片上方是待确认问题气泡，确认/取消行保持聊天流最底部
        chatContainer.addView(
            bubble,
            matchWidth().apply {
                gravity = if (mine) Gravity.END else Gravity.START
                marginStart = if (mine) 96 else 24
                marginEnd = if (mine) 24 else 96
                topMargin = 10
            }
        )
        scroll.post { scroll.fullScroll(android.view.View.FOCUS_DOWN) }
    }

    private fun startScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        beginScan()
    }

    private fun beginScan() {
        previewView.visibility = android.view.View.VISIBLE
        barcodeScanner = MlKitBarcodeScanner(this, previewView)
        barcodeScanner!!.scan { result ->
            runOnUiThread {
                previewView.visibility = android.view.View.GONE
                when (result) {
                    is ScanResult.Scanned -> {
                        appendBubble("扫到了 ${result.barcode}", mine = true, kind = UiKind.NORMAL)
                        render(viewModel.handleInput(result.barcode))
                    }
                    is ScanResult.NoMatch -> render(HomeUiState(UiKind.QUESTION, result.message))
                    is ScanResult.Error -> render(
                        HomeUiState(UiKind.ERROR, "${result.message}（可用键盘输入条码）")
                    )
                }
            }
        }
    }

    private fun render(state: HomeUiState) {
        taskText.text = state.currentTask
        val pending = state.pendingConfirmId != null
        // 确认/取消行保持聊天流最底部（在气泡之后）
        if (confirmRow.parent != null) chatContainer.removeView(confirmRow)
        chatContainer.addView(confirmRow, matchWidth())
        confirmRow.visibility = if (pending) android.view.View.VISIBLE else android.view.View.GONE
        appendBubble(state.reply, mine = false, kind = state.kind)
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

    /** 后台清理（Task 052）：切后台即释放相机。 */
    override fun onStop() {
        super.onStop()
        barcodeScanner?.cancel()
        previewView.visibility = android.view.View.GONE
    }

    override fun onDestroy() {
        super.onDestroy()
        barcodeScanner?.cancel()
    }
}
