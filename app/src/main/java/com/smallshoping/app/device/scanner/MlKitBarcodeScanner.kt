package com.smallshoping.app.device.scanner

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage

/**
 * MLKit 条码扫描实现（Task 052）：
 * standalone 版 barcode-scanning 库，离线可用，不依赖 Google Play 服务。
 *
 * 设备兼容（真机路径）：
 * - 无 CAMERA 权限 → [ScanResult.Error]（回退键盘输入，离线宪法 #6）；
 * - 无后置摄像头（如部分平板只有前置）→ 自动改用前置摄像头；
 * - 无任何摄像头/绑定失败/相机服务异常 → [ScanResult.Error]；
 * - 取景框挂在 [previewView]，Activity 须是 LifecycleOwner（ComponentActivity 等）。
 *
 * 会话契约：结果只回调一次；[cancel] 后迟到的帧回调会被丢弃（finished 标志）。
 */
class MlKitBarcodeScanner(
    private val activity: Activity,
    private val previewView: PreviewView
) : BarcodeScanner {

    private val scanner = BarcodeScanning.getClient()
    private var activeAnalysis: ImageAnalysis? = null

    /** 会话结束标志：成功、失败或 cancel 后置位，防止重复回调。 */
    private var finished = false

    override fun scan(onResult: (ScanResult) -> Unit) {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            finish(onResult, ScanResult.Error("NO_CAMERA_PERMISSION", "没有相机权限，请用键盘输入条码"))
            return
        }
        ProcessCameraProvider.getInstance(activity).addListener(
            {
                val provider = runCatching { ProcessCameraProvider.getInstance(activity).get() }.getOrNull()
                if (provider == null) {
                    finish(onResult, ScanResult.Error("CAMERA_UNAVAILABLE", "相机不可用，请用键盘输入条码"))
                    return@addListener
                }
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(ContextCompat.getMainExecutor(activity)) { imageProxy ->
                    if (finished) {
                        imageProxy.close()
                        return@setAnalyzer
                    }
                    val mediaImage = imageProxy.image
                    if (mediaImage != null) {
                        val inputImage = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees
                        )
                        scanner.process(inputImage)
                            .addOnSuccessListener { barcodes ->
                                imageProxy.close()
                                val barcode = barcodes.firstOrNull { it.rawValue != null }
                                    ?.rawValue?.takeIf { it.isNotBlank() }
                                if (barcode != null) {
                                    finish(onResult, ScanResult.Scanned(barcode))
                                }
                            }
                            .addOnFailureListener {
                                imageProxy.close()
                                finish(onResult, ScanResult.Error("SCAN_FAILED", it.message ?: "扫描失败"))
                            }
                    } else {
                        imageProxy.close()
                    }
                }
                activeAnalysis = analysis
                if (!bindCamera(provider, preview, analysis)) {
                    finish(onResult, ScanResult.Error("CAMERA_BIND_FAILED", "相机启动失败，请用键盘输入条码"))
                }
            },
            ContextCompat.getMainExecutor(activity)
        )
    }

    /** 绑定相机：优先后置（扫码常规方向），无后置自动降级前置（设备兼容）。 */
    private fun bindCamera(
        provider: ProcessCameraProvider,
        preview: Preview,
        analysis: ImageAnalysis
    ): Boolean {
        val lifecycleOwner = activity as? LifecycleOwner ?: return false
        provider.unbindAll()
        return runCatching {
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
        }.recoverCatching {
            // 无后置摄像头的设备（部分平板）降级前置
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis
            )
        }.isSuccess
    }

    override fun cancel() {
        finished = true
        runCatching { scanner.close() }
        activeAnalysis?.clearAnalyzer()
        activeAnalysis = null
    }

    private fun finish(onResult: (ScanResult) -> Unit, result: ScanResult) {
        if (finished) return
        finished = true
        runCatching { scanner.close() }
        activeAnalysis?.clearAnalyzer()
        activeAnalysis = null
        onResult(result)
    }
}
