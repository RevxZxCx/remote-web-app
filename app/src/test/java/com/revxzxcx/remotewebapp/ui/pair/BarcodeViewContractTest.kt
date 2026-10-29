package com.revxzxcx.remotewebapp.ui.pair

import android.content.Context
import android.os.Handler
import android.os.Message
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 锁死 zxing `BarcodeView` 的一次性契约——B002 的修复完全建立在这条契约上。
 *
 * ## 为什么需要这个测试
 *
 * B002（扫码配对失败后再也扫不出来）的根因不在我们的代码，而在 zxing 的
 * `decodeSingle()` 语义：
 *
 * 1. `decodeSingle(cb)` 只是把 `decodeMode` 置成 `SINGLE`、存下 `cb`、再尝试起解码线程；
 * 2. 解码线程一旦回传成功结果，`BarcodeView$1.handleMessage` 在交付 `cb.barcodeResult(...)`
 *    **之后**，只要 `decodeMode == SINGLE` 就调 `stopDecoding()`；
 * 3. 而 `stopDecoding()` 会把 `callback` 置成 `null`、`decodeMode` 置成 `NONE`。
 *
 * 于是「一次成功 = 一次性票据被撕掉」：同一个控件此后收到的任何结果，
 * 都会因为 `callback == null` 被静默丢弃，**控件本身毫发无损**。
 * 这就是为什么用户看到「扫码框还在、就是不再识别」，且只有退出页面重进才好——
 * 重进会走 `AndroidView.factory` 新建控件并重新 `decodeSingle()`。
 *
 * 修复的做法是**不拆控件、只重新武装**：对同一个 `BarcodeView` 再调一次 `decodeSingle()`。
 * 这个测试直接驱动真实控件验证上述两条契约，防止将来升级 zxing 时假设悄悄失效。
 *
 * ## 为什么不用相机
 *
 * 契约的判据在 `resultHandler` 这一层（解码线程把结果 `sendMessage` 给它），
 * 相机与解码线程只是这条边的上游。这里直接向 `resultHandler` 投递一条
 * `zxing_decode_succeeded` 消息，走的正是解码线程走的同一个 `Handler$Callback`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BarcodeViewContractTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** `BarcodeView$1`（`resultHandler` 的 `Handler$Callback`）就是解码线程回传结果的那条边。 */
    private fun resultCallbackOf(view: BarcodeView): Handler.Callback {
        val field = BarcodeView::class.java.getDeclaredField("resultCallback")
        field.isAccessible = true
        val callback = field.get(view) as Handler.Callback?
        assertNotNull("BarcodeView.resultCallback 取不到，zxing 内部结构变了", callback)
        return callback!!
    }

    private fun callbackOf(view: BarcodeView): BarcodeCallback? {
        val field = BarcodeView::class.java.getDeclaredField("callback")
        field.isAccessible = true
        return field.get(view) as BarcodeCallback?
    }

    private fun decodeModeOf(view: BarcodeView): Any? {
        val field = BarcodeView::class.java.getDeclaredField("decodeMode")
        field.isAccessible = true
        return field.get(view)
    }

    /** 构造一条解码成功消息，`what` 用的就是 zxing 自己的 `R$id.zxing_decode_succeeded`。 */
    private fun successMessage(text: String): Message {
        val result = Result(text, null, null, BarcodeFormat.QR_CODE)
        val message = Message.obtain()
        message.what = com.google.zxing.client.android.R.id.zxing_decode_succeeded
        message.obj = BarcodeResult(result, null)
        return message
    }

    @Test
    fun `扫到一次之后同一个控件就不再持有回调 这就是 B002 的根因`() {
        val view = BarcodeView(context)
        val received = mutableListOf<String>()
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let(received::add)
            }
        }

        view.decodeSingle(callback)
        assertNotNull("decodeSingle 之后控件必须持有回调", callbackOf(view))

        resultCallbackOf(view).handleMessage(successMessage("payload-1"))

        assertEquals(listOf("payload-1"), received)
        assertNull(
            "成功一次之后 callback 必须被 stopDecoding() 清空——否则 B002 的根因不成立，修复也就不必要",
            callbackOf(view),
        )
    }

    @Test
    fun `回调被清空后 同一条消息再投递也不会交付`() {
        val view = BarcodeView(context)
        val received = mutableListOf<String>()
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let(received::add)
            }
        }

        view.decodeSingle(callback)
        val handler = resultCallbackOf(view)
        handler.handleMessage(successMessage("payload-1"))
        // 第二次投递：此时 callback 已为 null，handleMessage 必须静默吞掉
        handler.handleMessage(successMessage("payload-2"))

        assertEquals(
            "第二次结果不得交付；控件不会坏，只是一次性票据用完了",
            listOf("payload-1"),
            received,
        )
    }

    @Test
    fun `对同一个控件重新 decodeSingle 就能再收一次 这正是修复的依据`() {
        val view = BarcodeView(context)
        val received = mutableListOf<String>()
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let(received::add)
            }
        }
        val handler = resultCallbackOf(view)

        view.decodeSingle(callback)
        handler.handleMessage(successMessage("payload-1"))
        assertNull(callbackOf(view))

        // 修复动作：不新建控件，只在同一实例上重新武装
        view.decodeSingle(callback)
        assertNotNull("重新 decodeSingle 必须把回调装回去", callbackOf(view))
        handler.handleMessage(successMessage("payload-2"))

        assertEquals(
            "重新武装之后必须能再收一次；「重新扫描」按钮就是干这件事",
            listOf("payload-1", "payload-2"),
            received,
        )
    }

    @Test
    fun `重新 decodeSingle 复用的是同一个控件实例 不会换掉控件`() {
        val view = BarcodeView(context)
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) = Unit
        }

        view.decodeSingle(callback)
        val beforeHandler = resultCallbackOf(view)
        val beforeMode = decodeModeOf(view)
        resultCallbackOf(view).handleMessage(successMessage("payload-1"))

        view.decodeSingle(callback)
        val afterHandler = resultCallbackOf(view)
        val afterMode = decodeModeOf(view)

        // 控件内部结构不变：同实例、同模式——重新武装不产生新对象，也不重建预览
        assertEquals("重新武装不得替换 resultHandler", beforeHandler, afterHandler)
        assertEquals("重新武装后应回到 SINGLE 模式", beforeMode, afterMode)
        assertEquals("SINGLE 模式必须是同一个枚举实例", "SINGLE", afterMode?.toString())
    }

    @Test
    fun `光 resume 不会把回调装回来 所以旧实现靠重建控件必然时好时坏`() {
        val view = BarcodeView(context)
        val received = mutableListOf<String>()
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let(received::add)
            }
        }

        view.decodeSingle(callback)
        resultCallbackOf(view).handleMessage(successMessage("payload-1"))
        assertNull("成功一次后回调已被清空", callbackOf(view))

        // 旧实现的做法：pause + 重建 + resume。这里只跑 resume 那半段。
        // pause() 不复位 decodeMode（字节码确认），resume() 走 previewStarted()->startDecoderThread()，
        // 而 startDecoderThread() 开头就判 decodeMode == NONE 直接 return。
        view.pause()
        view.resume()

        assertNull(
            "resume 不会复活回调：decodeMode 已是 NONE，startDecoderThread 直接 return。" +
                "旧实现只能赌 AndroidView 重建，而重建要抢同一个摄像头，所以才会「有时能扫有时不能」",
            callbackOf(view),
        )
        resultCallbackOf(view).handleMessage(successMessage("payload-2"))
        assertEquals(listOf("payload-1"), received)
    }

    @Test
    fun `stopDecoding 之后必须靠 decodeSingle 才能恢复 不能指望旧回调复活`() {
        val view = BarcodeView(context)
        val received = mutableListOf<String>()
        val callback = object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                result?.text?.let(received::add)
            }
        }

        view.decodeSingle(callback)
        view.stopDecoding()

        assertNull("stopDecoding 必须清空回调", callbackOf(view))
        assertEquals("stopDecoding 必须回到 NONE", "NONE", decodeModeOf(view)?.toString())

        // 不重新武装就直接投递：不得交付
        resultCallbackOf(view).handleMessage(successMessage("payload-x"))
        assertEquals(emptyList<String>(), received)

        // 重新武装后恢复
        view.decodeSingle(callback)
        resultCallbackOf(view).handleMessage(successMessage("payload-y"))
        assertEquals(listOf("payload-y"), received)
    }
}
