package com.revxzxcx.remotewebapp.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.session.ChatItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R015 审查 P1-1/P1-2 的回归守卫：**永不重启的 effect 持有的 lambda 不能按值捕获普通 val**。
 *
 * 缺陷形态（R015 自己踩的，也是 m21177-2.3 与 R010m 的同族第三次）：
 *
 * ```
 * val rows = remember(state.items) { foldChatItems(state.items) }   // 普通 val
 * LaunchedEffect(listState) {                                       // key 永不重启
 *     snapshotFlow { rows.size }.collect { scrollToItem(it - 1) }    // 按值捕获
 * }
 * ```
 *
 * `LaunchedEffect(listState)` 的 key 在整场会话里不变 → effect 不重启 → 协程持有的是
 * **首次组合**创建的那个 lambda → `rows.size` 永远等于首帧值。开会话时 `items` 通常为空，
 * 于是 `scrollToBottom(0)` 把列表跳到顶部，流式过程中每个 token 拿到的仍是 0 → 永久不跟随。
 * 同一根因打到 [ChatScrollbar] 的 `prefixes` 与 `resolve()`，症状是「我发送的消息」标记
 * 随聊天增多逐个消失、滑块与点击定位整体偏移。
 *
 * 定稿实现是 `val rows by remember { derivedStateOf { foldChatItems(state.items) } }`：
 * lambda 捕获的是**委托**，调用时才读当前值。这里用一个最小复现脚手架把「按值捕获会冻住」
 * 与「按委托捕获不会」钉成两条可执行断言，使这类回归在没有真机、没有 ChatScreen 集成测试的
 * 情况下也能被抓住。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ChatFoldCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    /** 采集到的行数序列，用来观察 lambda 到底读到的是活值还是冻住的值。 */
    private val seen = mutableListOf<Int>()

    /** 数据源：模拟 `state.items`。 */
    private val source = mutableStateOf(List(3) { "row-$it" })

    /** 「合龙」用例里 LazyColumn 的滚动状态，断言时从组合外面读。 */
    private var followState: LazyListState? = null

    /** 「合龙」用例里折叠行模型的行数，断言时从组合外面读。 */
    private var rowCount: Int = 0

    /**
     * @param frozen true 用「普通 `remember` val + 按值捕获」（缺陷形态），
     *   false 用「`by derivedStateOf` + 读委托」（定稿形态）。
     */
    private fun setContent(frozen: Boolean) {
        compose.setContent {
            val listState = rememberLazyListState()

            // 与 ChatScreen 同构：折叠行模型 + 一个 key 只有 listState 的 effect。
            val frozenRows = remember(source.value) { source.value.map { it.uppercase() } }
            val liveRows by remember { derivedStateOf { source.value.map { it.uppercase() } } }

            LaunchedEffect(listState) {
                snapshotFlow {
                    if (frozen) frozenRows.size else liveRows.size
                }.collect { seen += it }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(state = listState) {
                    items(liveRows.size) { index -> Text(text = liveRows[index]) }
                }
            }
        }
    }

    @Test
    fun `按值捕获的普通val会被冻在首次组合`() {
        setContent(frozen = true)
        compose.waitForIdle()

        val before = seen.toList()
        compose.runOnIdle { source.value = List(9) { "row-$it" } }
        compose.waitForIdle()

        // 数据源从 3 条涨到 9 条，但 effect 里的 lambda 仍读到首帧的 3。
        assertEquals("首帧应看到 3", listOf(3), before)
        assertEquals("冻住之后仍是 3，不会随数据源增长", listOf(3), seen.toList())
    }

    @Test
    fun `按委托捕获的派生状态不会被冻住`() {
        setContent(frozen = false)
        compose.waitForIdle()

        compose.runOnIdle { source.value = List(9) { "row-$it" } }
        compose.waitForIdle()

        // 3 → 9，effect 里的 lambda 每次都读到当前值。
        assertEquals("应该看到 3 之后是 9", listOf(3, 9), seen.toList())
    }

    /**
     * 「合龙」版：真实 [foldChatItems] + 真实 [scrollToBottom] + 真实 `LazyColumn`
     * （带 key）串起来，验证**行数增长之后按新下标滚仍然能到底**。
     *
     * 与另外两条用例的分工：那两条证明「按值捕获会冻住 / 按委托捕获不会」这个语义本身；
     * 这一条证明真实行模型、真实滚动函数、真实带 key 的列表三者接在一起没有问题
     * （`foldChatItems` 的 key 是否唯一、`scrollToBottom` 在 key 化列表上是否照旧生效，
     * 都是只有把三者串起来才会现形的）。
     *
     * 刻意不依赖 `LaunchedEffect` 的自动跟随：Robolectric 下 `withFrameNanos` 要靠
     * `mainClock.advanceTimeBy` 才推帧，那条路由 `ChatScrollFollowTest` 专门覆盖
     * （它自己的脚手架的 frame 推进已经调通）。这里手动滚，避免两个机制互相干扰。
     */
    @Test
    fun `行数增长后按新下标滚仍能到底`() {
        val items = mutableStateOf(
            listOf(
                tool(10, "read"),
                user(11),
                tool(12, "write"),
                user(13),
                tool(14, "pwsh"),
                user(15),
                tool(16, "read"),
                user(17),
                tool(18, "write"),
                user(19),
            ),
        )
        compose.setContent {
            val listState = rememberLazyListState()
            followState = listState
            val rows by remember { derivedStateOf { foldChatItems(items.value) } }
            rowCount = rows.size
            Box(modifier = Modifier.height(200.dp)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(rows, key = { _, row -> row.key }) { _, row ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                        ) {
                            Text(text = row.key.toString())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        val before = requireNotNull(rowCount)
        // 追加一条用户消息：它会把最后一个分组 flush 出来，行数 +2（分组 + 用户行）。
        compose.runOnIdle {
            items.value = items.value + listOf(tool(20, "pwsh"), user(21))
        }
        compose.waitForIdle()

        val after = requireNotNull(rowCount)
        assertTrue("追加一行后行数应当增长（$before → $after）", after > before)

        compose.runOnIdle {
            kotlinx.coroutines.runBlocking {
                followState?.scrollToBottom(after - 1)
            }
        }
        compose.runOnIdle {
            val info = followState!!.layoutInfo
            assertFalse(
                "按新下标滚应当到底（total=${info.totalItemsCount}" +
                    " firstVisible=${followState!!.firstVisibleItemIndex}" +
                    " lastVisible=${info.visibleItemsInfo.lastOrNull()?.index}）",
                followState!!.canScrollForward,
            )
        }
    }

    @Test
    fun `折叠行模型的key对前插稳定`() {
        // loadMore 往前插历史：前插页最新一条是非独立项（tool-call），与现第一个分组合并。
        // key 必须不变，否则 LazyColumn 的 key 锚点失效 → 列表位置跳、展开态丢。
        val before = foldChatItems(
            listOf(
                tool(10, "read"),
                user(11),
                tool(12, "write"),
            ),
        )
        val after = foldChatItems(
            listOf(
                tool(5, "pwsh"),
                tool(10, "read"),
                user(11),
                tool(12, "write"),
            ),
        )

        // 第一个分组现在多了一个成员，但封住它的独立条目仍是 seq=11 的用户消息。
        val firstBefore = before.first { it is ChatRow.Group }
        val firstAfter = after.first { it is ChatRow.Group }
        assertEquals("前插后第一个分组的 key 必须不变", firstBefore.key, firstAfter.key)
        assertEquals("前插后第一个分组确实多了一个成员", 2, (firstAfter as ChatRow.Group).members.size)
        assertEquals("前插的成员排在最前面", 5L, (firstAfter.members.first()).seq)
    }

    @Test
    fun `折叠行模型的key在流式追加时稳定`() {
        // 追加只往最后一个分组里加成员，封住它的独立条目还没到 → key 保持 tail。
        val base = foldChatItems(listOf(tool(10, "read")))
        val grown = foldChatItems(
            listOf(
                tool(10, "read"),
                tool(11, "write"),
                tool(12, "pwsh"),
            ),
        )
        assertEquals("流式追加不改分组 key", base.single().key, grown.single().key)
    }

    @Test
    fun `分组key不会与单独行的key相撞`() {
        val rows = foldChatItems(
            listOf(
                tool(10, "read"),
                user(11),
            ),
        )
        val keys = rows.map { it.key }
        assertEquals("LazyColumn 要求 key 全局唯一", keys.size, keys.distinct().size)
    }

    private fun tool(seq: Long, name: String) = ChatItem.ToolCall(
        seq = seq,
        time = 0L,
        turn = 0L,
        step = 0L,
        callId = "call-$seq",
        name = name,
        arguments = "{}",
    )

    private fun user(seq: Long) = ChatItem.User(
        seq = seq,
        time = 0L,
        text = "用户消息 $seq",
    )
}
