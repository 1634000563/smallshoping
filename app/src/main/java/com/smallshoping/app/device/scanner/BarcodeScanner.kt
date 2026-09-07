package com.smallshoping.app.device.scanner

/**
 * 条码扫描结果（spec 10 §1：EAN-13/EAN-8/UPC-A/UPC-E/Code128/Code39 由 MLKit 识别，
 * 端口只回传原始条码串，不识别编码类型）。
 */
sealed interface ScanResult {
    data class Scanned(val barcode: String) : ScanResult
    data class NoMatch(val message: String) : ScanResult
    data class Error(val code: String, val message: String) : ScanResult
}

/**
 * 条码扫描端口（Task 052）：摄像头实时扫码（MLKit standalone，离线可用）。
 * 扫码结果是确定性输入（spec 10 §5），汇入与语音/键盘相同的输入链路；
 * 设备无摄像头/权限被拒/绑定失败时回调 [ScanResult.Error]，调用方回退键盘输入
 * （离线宪法 #6：设备不可用必须有人工降级路径）。
 *
 * 契约：
 * - [scan] 结果只回调一次（成功/失败/取消之后不再回调）；
 * - [cancel] 释放会话，可安全重复调用；
 * - 一个实例对应一次扫描会话，会话结束后调用方应丢弃实例。
 */
interface BarcodeScanner {

    /** 开始扫描；结果经回调返回（主线程）。 */
    fun scan(onResult: (ScanResult) -> Unit)

    /** 取消/释放扫描会话。 */
    fun cancel()
}
