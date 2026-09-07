package com.smallshoping.app.feature.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
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
import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.device.scanner.BarcodeScanner
import com.smallshoping.app.device.scanner.MlKitBarcodeScanner
import com.smallshoping.app.device.scanner.ScanResult

/**
 * 极简主界面（Task 041/042，Task 059 微信式输入重构）：
 * 聊天窗口布局——回复区 + 底部输入栏（点输入框弹系统键盘）。
 *
 * - 无传统菜单（Route Guard）；老板只会看到：回复区、当前任务、
 *   底部输入栏（扫+输入框+发送）、确认/取消、人工兜底按钮；
 * - 所有输入汇入 [HomeViewModel.handleInput]（唯一链路 UI→AI→Tool→Domain）；
 * - 确认卡片：CONFIRM 状态黄色卡片提示；异常 ERROR 红色提示（AI 挂不影响营业）；
 * - 人工接管：AI 不可用时「手动加」「人工结账」直接走 Domain（Gate A 同一事实）；
 * - 语音输入由系统键盘提供（ADR-017）：点输入框弹出的键盘自带语音键
 *   （小米/搜狗/讯飞等输入法，方言效果好），应用不内置 ASR、不申请麦克风权限；
 * - 扫码失败自动降级提示用键盘输入（离线宪法 #6）；
 * - 基类为 ComponentActivity：相机绑定需要 LifecycleOwner（Task 052）。
 */
class MainActivity : ComponentActivity() {

    private val root = CompositionRoot()
    private lateinit var viewModel: HomeViewModel
    private lateinit var replyText: TextView
    private lateinit var taskText: TextView
    private lateinit var input: EditText
    private lateinit var confirmButton: Button
    private lateinit var cancelButton: Button
    private lateinit var confirmRow: LinearLayout
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
        }

        // 当前任务区
        taskText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(32, 16, 32, 8)
            text = "当前没有待结账的单子"
        }
        rootLayout.addView(taskText, matchWidth())

        // 回复区（聊天内容，可滚动，占满剩余空间）
        replyText = TextView(this).apply {
            textSize = 20f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(32, 16, 32, 16)
            text = "说什么：卖两斤土豆 / 进100斤土豆成本2块8 / 结账 / 今天卖了多少钱"
        }
        val scroll = ScrollView(this).apply {
            addView(replyText, matchWidth())
            isFillViewport = true
        }
        rootLayout.addView(scroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        // 确认卡片行（仅待确认时显示，Task 042）
        confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = android.view.View.GONE
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

        // 扫码取景框（扫码时临时显示，Task 052）
        previewView = PreviewView(this).apply { visibility = android.view.View.GONE }
        rootLayout.addView(previewView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 320
        ))

        // 人工接管行：AI 不可用时的兜底（同一 Domain，Gate A）
        val manualRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 0, 16, 0)
        }
        manualRow.addView(
            Button(this).apply {
                text = "手动加（如：土豆 2斤）"
                setOnClickListener {
                    val raw = input.text.toString().trim()
                    val parts = raw.split(Regex("\\s+"), limit = 2)
                    val state = if (parts.size == 2) {
                        viewModel.manualAddItem(parts[0], parts[1])
                    } else {
                        HomeUiState(UiKind.ERROR, "手动加格式：商品名 数量（如 土豆 2斤）")
                    }
                    render(state)
                    input.text.clear()
                }
            },
            wrap()
        )
        manualRow.addView(
            Button(this).apply {
                text = "人工结账"
                setOnClickListener { render(viewModel.manualCheckout()) }
            },
            wrap()
        )
        manualRow.addView(
            Button(this).apply {
                text = "查账"
                setOnClickListener {
                    startActivity(
                        android.content.Intent(this@MainActivity, com.smallshoping.app.feature.report.FallbackActivity::class.java)
                    )
                }
            },
            wrap()
        )
        rootLayout.addView(manualRow, matchWidth())

        // 底部输入栏（微信式，Task 059）：[扫] [输入框（点按弹键盘，键盘自带语音）] [发送]
        val inputBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 12, 16, 20)
        }
        inputBar.addView(
            Button(this).apply {
                text = "扫"
                setOnClickListener { startScan() }
            },
            wrap()
        )
        input = EditText(this).apply {
            hint = "打字说，或按住键盘的语音键说（条码直接输入数字）"
            textSize = 16f
            maxLines = 3
            setPadding(40, 20, 40, 20)
            // 微信式圆角灰底输入框
            background = GradientDrawable().apply {
                cornerRadius = 56f
                setColor(Color.rgb(245, 245, 245))
            }
        }
        inputBar.addView(input, LinearLayout.LayoutParams(0, wrapHeight(), 1f).apply {
            marginStart = 16
            marginEnd = 16
        })
        inputBar.addView(
            Button(this).apply {
                text = "发送"
                setBackgroundColor(Color.rgb(7, 193, 96)) // 微信绿
                setTextColor(Color.WHITE)
                setOnClickListener {
                    render(viewModel.handleInput(input.text.toString()))
                    input.text.clear()
                }
            },
            wrap()
        )
        rootLayout.addView(inputBar, matchWidth())
        return rootLayout
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
                    is ScanResult.Scanned -> render(viewModel.handleInput(result.barcode))
                    is ScanResult.NoMatch -> render(HomeUiState(UiKind.QUESTION, result.message))
                    is ScanResult.Error -> render(
                        HomeUiState(UiKind.ERROR, "${result.message}（可用键盘输入条码）")
                    )
                }
            }
        }
    }

    private fun render(state: HomeUiState) {
        replyText.text = state.reply
        taskText.text = state.currentTask
        val pending = state.pendingConfirmId != null
        confirmRow.visibility = if (pending) android.view.View.VISIBLE else android.view.View.GONE
        when (state.kind) {
            // 确认卡片：黄色背景强调
            UiKind.CONFIRM -> {
                replyText.setBackgroundColor(Color.YELLOW)
                replyText.setTextColor(Color.BLACK)
            }

            // 异常：红色提示（AI 不可用/业务失败，不阻塞营业）
            UiKind.ERROR -> {
                replyText.setBackgroundColor(Color.WHITE)
                replyText.setTextColor(Color.RED)
            }

            // 追问/普通：常规样式
            else -> {
                replyText.setBackgroundColor(Color.WHITE)
                replyText.setTextColor(Color.BLACK)
            }
        }
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
