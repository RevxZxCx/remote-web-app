package com.revxzxcx.remotewebapp

import android.app.Application
import com.revxzxcx.remotewebapp.core.AppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 进程级的输入法 inset 桥（用户 m21177 附加反馈的修复载体）。
 *
 * **为什么需要它**：MainActivity 调了 `enableEdgeToEdge()`
 * （= `WindowCompat.setDecorFitsSystemWindows(window, false)`），Android 15 起
 * `EDGE_TO_EDGE_ENFORCED` 又让 `adjustResize` 彻底失效，窗口不再为输入法收缩。
 * 按文档应当由 Compose 的 `Modifier.imePadding()` 消费 `WindowInsets.ime`，
 * 但**在 MuMu（Android 15 + 搜狗输入法）上实测 `WindowInsets.ime` 恒为 0**
 * （`ime bottom=0.0.dp`，而同一时刻 `WindowInsets.statusBars` 正常返回 41dp，
 * 说明 inset 通道是通的、只有 ime 这一路没有值）。
 *
 * 所以退回框架层：在 DecorView 上挂 `OnApplyWindowInsetsListener` 直接读
 * `WindowInsetsCompat.Type.ime()`（这是不受 Compose 影响的原生路径），
 * 把像素高度放进这个 StateFlow，再由 AppRoot 换算成 dp 做 padding。
 *
 * 放在 Application 上而不是 Activity：输入法弹起/收起要跨 Activity 重建保持，
 * 而 `rememberSaveable` 存不了像素值。
 */
object ImeInsets {

    private val _bottomPx = MutableStateFlow(0)

    /** 输入法占据的像素高度；0 表示输入法没有弹出。 */
    val bottomPx: StateFlow<Int> = _bottomPx

    /** 由 MainActivity 的 inset 监听器调用。 */
    fun update(bottomPx: Int) {
        if (_bottomPx.value != bottomPx) _bottomPx.value = bottomPx
    }
}

/**
 * 应用入口，持有唯一的 [AppContainer]。
 *
 * 本期不引入 Hilt：模块数量还少，一个手工容器 + `ViewModelProvider.Factory` 足够，
 * 也少一个注解处理器（决策记录 D05 的补充）。
 */
class DshApplication : Application() {

    /** 应用级依赖容器，进程内唯一。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
