package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [insightsSwipe] 的真实手势测试（R051 半屏分区）。
 *
 * ## 守的是什么
 *
 * 1. **左半屏起手不能碰洞察**：过去这里有一条 `reveals.isEmpty()` 的断言守「左缘 24dp
 *    让给抽屉」。R051 把让出的区域从左缘一条窄带扩大到**整个左半屏**，同一个断言
 *    现在守的是一个宽得多的约定。
 * 2. **右半屏左滑才揭开**：方向必须与面板所在侧一致。往右拖（把面板往屏幕外推）不该
 *    揭开，但**仍然要消费事件**——否则抽屉的 `anchoredDraggable` 会把整屏的右滑
 *    都当成开抽屉（真机实测过）。
 * 3. **松手判定用的是揭开的比例**，不是起手的位置。
 *
 * ## 为什么用 `awaitFirstDown` 而不是 `detectHorizontalDragGestures`
 *
 * `detectHorizontalDragGestures` 的 `onDragStart` 给的是**越过触摸阈值那一刻**的坐标，
 * 不是按下点。快速右滑时手指已经跑出一两个事件（真机实测 40px≈13dp 按下、回调收到
 * 126px≈42dp）→ 起点被误判到另一半屏。这是 T076 的真机缺陷④，本条测试守的就是它。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class InsightsSwipeRenderTest {

    @get:Rule
    val compose = createComposeRule()

    private val reveals = mutableListOf<Float>()
    private var settled: Boolean? = null
    private var widthPx = 0f

    /**
     * 父级在 [PointerEventPass.Final] 采到的「这一帧位移被吃了吗」。
     *
     * 为什么必须有这个观察器：`change.consume()` 的效果**在本宿主里不可见**——
     * 测试里只有洞察手势一个消费者，吃不吃都没人跟它抢。但它守的恰恰是「右半屏
     * 往右拖时也要把事件吃掉，否则会话抽屉的 `anchoredDraggable` 会覆盖整屏、
     * 从右边滑也能把左抽屉拉出来」。首轮变异（改成只在揭开方向消费）就是因此
     * 漏网的。Final pass 由根向叶派发、排在 Main 之后，此时 `isConsumed` 已经
     * 反映了子节点的决定，是唯一能看到这件事的位置。
     */
    private val consumptionSamples = mutableListOf<Boolean>()

    /**
     * @param mode 见 [SwipeMode]。
     * @param initial 初始揭开比例。COLLAPSE 场景必须传 1f——那代表「抽屉已经全开」，
     *   生产代码里 `insightsReveal` 此时就是 1。用 0 起手的话「往右推」其实是在把
     *   一个关着的面板推开，测的就不是收起路径了（首版就是这样，两条断言都在测别的事）。
     */
    private fun setHost(mode: SwipeMode = SwipeMode.REVEAL, initial: Float = 0f) {
        reveals.clear()
        settled = null
        consumptionSamples.clear()
        compose.setContent {
            var reveal by remember { mutableFloatStateOf(initial) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Final)
                                event.changes.forEach { change ->
                                    // 采样条件是「这一帧有位移」，**不是「横向有位移」**。
                                    // 早先只采 dx != 0，于是纯竖直拖动永远采不到帧，
                                    // 「竖直拖动一帧都不该消费」就变成了恒真断言——
                                    // 变异验证里「竖直也接管」因此漏网（R052 M1）。
                                    val delta = change.positionChangeIgnoreConsumed()
                                    if (delta != Offset.Zero) {
                                        consumptionSamples += change.isConsumed
                                    }
                                }
                            }
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .testTag(TAG)
                        .fillMaxSize()
                        .insightsSwipe(
                            reveal = { reveal },
                            onReveal = {
                                reveal = it
                                reveals += it
                            },
                            onSettle = { settled = it },
                            mode = mode,
                        ),
                )
            }
        }
        // 视口宽度只能从语义节点量，`LocalDensity` 给不了（那是密度不是尺寸）。
        widthPx = compose.onNodeWithTag(TAG).fetchSemanticsNode().size.width.toFloat()
    }

    @Test
    fun leftHalfSwipeRightNeverTouchesInsights() {
        setHost()
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            // 起点在左半屏的最右侧（半屏边界内 1px），往右拖到屏幕右缘。
            swipe(
                start = Offset(widthPx / 2f - 1f, y),
                end = Offset(widthPx - 10f, y),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertTrue("左半屏起手不该产生任何揭开量，实际 $reveals", reveals.isEmpty())
        assertNull("左半屏起手不该触发松手判定，实际 $settled", settled)
        // 左半屏更要紧的是**不消费**：那半边的右滑必须原封不动地交给抽屉的
        // `anchoredDraggable`。我们连第一帧都不该吃——吃了抽屉就再也拉不出来。
        assertTrue(
            "左半屏的位移一帧都不该被洞察消费，实际 $consumptionSamples",
            consumptionSamples.none { it },
        )
    }

    @Test
    fun rightHalfSwipeLeftOpensInsights() {
        setHost()
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            // 右半屏起手往左拖满整个宽度 → 揭开量远超 35% 阈值。
            swipe(
                start = Offset(widthPx / 2f + 40f, y),
                end = Offset(10f, y),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertTrue("右半屏左滑应该产生揭开量", reveals.isNotEmpty())
        assertTrue("揭开量应该为正，实际 $reveals", reveals.all { it >= 0f })
        assertNotNull("松手后应该触发一次判定", settled)
        assertEquals("拖满整屏应该判定为打开", true, settled)
        // R052 加了轴向锁定：起手前几帧要先看清主方向，那时**不该**消费
        // （消费者一旦出现，滚动容器就再也拿不到这段手势了）。判出水平之后的
        // 每一帧都必须消费，否则抽屉的 `anchoredDraggable` 会把同一次拖动也当成
        // 左抽屉的开合。
        assertTrue("揭开方向必须消费指针事件", consumptionSamples.isNotEmpty())
        assertTrue(
            "判出水平轴之后必须每一帧都消费，实际 $consumptionSamples",
            consumptionSamples.last(),
        )
        assertTrue(
            "只剩最后几帧才消费说明轴向锁定拖太久，实际 $consumptionSamples",
            consumptionSamples.count { it } >= consumptionSamples.size - 2,
        )
    }

    @Test
    fun rightHalfSwipeRightIsConsumedButNeverReveals() {
        setHost()
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            // 右半屏往右拖：把面板推向屏幕外，揭开量应恒为 0，但事件必须被吃掉。
            // 无法在 Robolectric 里断言「抽屉没被拉出来」（那是另一个组件的状态），
            // 能断言的是「没有揭开量」与「松手判定为不打开」。
            swipe(
                start = Offset(widthPx / 2f + 40f, y),
                end = Offset(widthPx - 10f, y),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertTrue("反向拖不该有正的揭开量，实际 $reveals", reveals.all { it == 0f })
        assertEquals("反向拖松手应该回弹", false, settled)
        // 反向拖也必须消费：抽屉的 `anchoredDraggable` 覆盖整屏，不消费的话
        // 从右半屏往右滑会把**左边**的会话抽屉拉出来。这条断言是本文件里
        // 唯一能看见 `change.consume()` 的地方（见 `consumptionSamples` 的 KDoc）。
        assertTrue("反向拖必须有位移帧被采样到，实际 ${consumptionSamples.size}", consumptionSamples.isNotEmpty())
        // 同上：起手几帧用于判轴，判出水平后必须帧帧消费。
        // 恒不消费会让右半屏往右滑把**左边**的会话抽屉拉出来。
        assertTrue(
            "判出水平轴之后必须每一帧都消费，实际 $consumptionSamples",
            consumptionSamples.last(),
        )
    }

    @Test
    fun settledValueReflectsTheFinalRevealNotTheStart() {
        setHost()
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            // 只拖 8% 视口宽，远低于 35% 阈值。
            swipe(
                start = Offset(widthPx / 2f + 40f, y),
                end = Offset(widthPx / 2f + 40f - widthPx * 0.08f, y),
                durationMillis = 600,
            )
        }
        compose.waitForIdle()
        assertEquals("小幅拖动松手应该回弹", false, settled)
    }

    // ---------- COLLAPSE 模式（抽屉已开，挂在抽屉本体上） ----------

    @Test
    fun collapseModeDraggingRightClosesTheSheet() {
        setHost(mode = SwipeMode.COLLAPSE, initial = 1f)
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            // 必须拖满整屏：reveal 减小的量是 `拖过的像素 / 屏宽`，只拖半屏
            // 从 1.0 只能掉到 0.5，测不出「真的在收」。首版就是这里写短了。
            swipe(
                start = Offset(20f, y),
                end = Offset(widthPx - 10f, y),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertTrue("收起模式必须有位移帧", reveals.isNotEmpty())
        assertTrue("抽屉开着时往右推必须让揭开量下降，实际 $reveals", reveals.last() < 0.5f)
        // 收起方向也必须消费事件：抽屉本体盖在遮罩之上，不消费的话这一划会穿到
        // 下面的遮罩 `clickable` 上（表现为「滑一下抽屉就直接消失」而不是跟手）。
        // 同上：起手几帧用于判轴不消费，判出水平之后必须帧帧消费。
        assertTrue(
            "收起方向的位移帧必须在判出水平轴后被消费，实际 $consumptionSamples",
            consumptionSamples.isNotEmpty() && consumptionSamples.last(),
        )
    }

    @Test
    fun collapseModeStartsFromTheLeftEdgeTooSoTheSheetCanAlwaysBeDismissed() {
        setHost(mode = SwipeMode.COLLAPSE, initial = 1f)
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(
                start = Offset(1f, y),
                end = Offset(widthPx - 10f, y),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertTrue("收起模式在左缘起手也必须生效，实际 $reveals", reveals.isNotEmpty())
        assertEquals("拖满整屏应该判定为收起", false, settled)
    }

    @Test
    fun collapseModeDraggingLeftDoesNotGoPastFull() {
        setHost(mode = SwipeMode.COLLAPSE, initial = 1f)
        val y = 400f
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(
                start = Offset(widthPx * 0.6f, y),
                end = Offset(20f, y),
                durationMillis = 300,
            )
        }
        compose.waitForIdle()
        assertTrue("揭开量必须留在 [0,1]，实际 $reveals", reveals.all { it in 0f..1f })
    }

    // ---------- 纯点击不得触发松手判定（第二轮审查 P1 回归） ----------

    @Test
    fun pureTapOnTheScrimNeverReopensTheSheet() {
        // 第二轮审查 P1 的回归现场：遮罩补上 COLLAPSE 之后，一次**纯点击**也会走完
        // `horizontalDrag`（不越过 touchSlop 就抬手 → completed = true），那一刻
        // reveal 还是 1，`settleInsightsCollapse(1f)` 判为「开」→ 遮罩的
        // `clickable { settleInsights(false) }` 刚发起关闭，紧接着这里又打开，
        // `insightsJob.cancel()` 把关闭动画掐掉再动画回全开：抽屉左侧那条约 40dp
        // 的窄缝彻底点不掉。
        //
        // 只能测一种组合：`compose.setContent` 全局只允许调用一次，宿主无法在同一
        // 个测试方法里反复重建。选 COLLAPSE + 全开，因为那正是回归发生的那一格。
        setHost(mode = SwipeMode.COLLAPSE, initial = 1f)
        compose.onNodeWithTag(TAG).performTouchInput {
            // 手写 down+up：`TouchInjectionScope` 的这版没有 `click`。
            down(Offset(widthPx * 0.3f, 400f))
            up()
        }
        compose.waitForIdle()
        assertNull("遮罩上的纯点击不该触发松手判定，实际 settled=$settled", settled)
    }

    @Test
    fun aRealDragStillSettles() {
        // 对照组：同样的宿主，只要有位移就必须判定，别把 moved 写成恒 false。
        setHost(mode = SwipeMode.COLLAPSE, initial = 1f)
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(start = Offset(20f, 400f), end = Offset(widthPx - 10f, 400f), durationMillis = 400)
        }
        compose.waitForIdle()
        assertNotNull("真实拖动必须触发松手判定", settled)
    }

    @Test
    fun verticalDragNeverTouchesInsightsAndNeverConsumes() {
        // R052 需求 1（用户 m37463 第 1 条）：洞察抽屉展开时上下拖动会被判成右滑，
        // 抽屉被收起、内容滚不动。修法是起手先判主轴，竖直为主就整段让给滚动容器。
        //
        // 这条同时守两件事：
        // 1. 竖直拖**不产生任何揭开量**（不跟手乱动）；
        // 2. 竖直拖**一帧都不消费**——一旦消费，抽屉里的 verticalScroll / LazyColumn
        //    就再也拿不到这段手势，用户滑不动列表。
        setHost(mode = SwipeMode.COLLAPSE, initial = 1f)
        compose.onNodeWithTag(TAG).performTouchInput {
            swipe(
                start = Offset(widthPx / 2f + 40f, 200f),
                end = Offset(widthPx / 2f + 40f, 1200f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        assertTrue("竖直拖动不该产生揭开量，实际 $reveals", reveals.isEmpty())
        assertNull("竖直拖动不该触发松手判定，实际 $settled", settled)
        assertTrue(
            "竖直拖动必须一帧都不消费（否则列表滚不动），实际 $consumptionSamples",
            consumptionSamples.none { it },
        )
    }

    private companion object {
        const val TAG = "insights-swipe-host"
    }
}
