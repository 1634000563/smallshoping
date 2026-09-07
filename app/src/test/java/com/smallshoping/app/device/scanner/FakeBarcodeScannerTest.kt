package com.smallshoping.app.device.scanner

import com.smallshoping.app.app.di.CompositionRoot
import com.smallshoping.app.ai.orchestrator.OrchestratorReply
import com.smallshoping.app.core.common.normalize
import com.smallshoping.app.core.money.Money
import com.smallshoping.app.core.quantity.Unit as QuantityUnit
import com.smallshoping.app.domain.catalog.BarcodeType
import com.smallshoping.app.domain.catalog.Product
import com.smallshoping.app.domain.catalog.ProductBarcode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Task 052：扫码结果汇入统一输入链路（与语音/键盘同一条链路，spec 10 §5）。 */
class FakeBarcodeScannerTest {

    /** 测试用假扫码器（device 局部规则：真实设备行为必须有假实现供单测）。 */
    private class FakeBarcodeScanner(private val result: ScanResult) : BarcodeScanner {
        var canceled = false
            private set

        override fun scan(onResult: (ScanResult) -> Unit) {
            onResult(result)
        }

        override fun cancel() {
            canceled = true
        }
    }

    @Test
    fun `扫码成功：条码汇入与键盘相同的输入链路报出商品`() {
        val root = CompositionRoot()
        root.products.saveProduct(
            Product(
                id = "P-1", storeId = "STORE-1", name = "土豆", normalizedName = normalize("土豆"),
                saleUnit = QuantityUnit.JIN, purchaseUnit = QuantityUnit.JIN,
                currentSalePrice = Money(380), currentCostPrice = null
            )
        )
        root.products.addBarcode(
            ProductBarcode(
                productId = "P-1", barcode = "6901234567890",
                barcodeType = BarcodeType.EAN13, isPrimary = true
            )
        )
        var captured: ScanResult? = null
        FakeBarcodeScanner(ScanResult.Scanned("6901234567890")).scan { captured = it }

        // 与键盘输入相同的链路（spec 10：扫码是通用输入方式）
        val reply = root.orchestrator.handle(
            root.inputAdapter.fromText((captured as ScanResult.Scanned).barcode)
        )
        assertTrue(reply is OrchestratorReply.Text)
        assertTrue((reply as OrchestratorReply.Text).text.contains("土豆"))
    }

    @Test
    fun `扫码失败：错误降级提示，业务不受影响`() {
        val root = CompositionRoot()
        var captured: ScanResult? = null
        FakeBarcodeScanner(ScanResult.Error("NO_CAMERA", "没有相机")).scan { captured = it }
        assertTrue(captured is ScanResult.Error)
        assertEquals("NO_CAMERA", (captured as ScanResult.Error).code)
        // 错误场景下键盘仍可用（离线宪法 #6）
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("今天卖了多少钱"))
        assertTrue(reply is OrchestratorReply.Text)
    }

    @Test
    fun `未识别：NoMatch 明确回调，不伪造成功也不影响链路`() {
        val root = CompositionRoot()
        var captured: ScanResult? = null
        FakeBarcodeScanner(ScanResult.NoMatch("没有识别到条码")).scan { captured = it }
        assertTrue(captured is ScanResult.NoMatch)
        assertEquals("没有识别到条码", (captured as ScanResult.NoMatch).message)
        val reply = root.orchestrator.handle(root.inputAdapter.fromText("卖两斤土豆"))
        assertTrue(reply is OrchestratorReply.Text)
    }

    @Test
    fun `取消：cancel 可达`() {
        val fake = FakeBarcodeScanner(ScanResult.Scanned("x"))
        fake.cancel()
        assertTrue(fake.canceled)
    }
}
