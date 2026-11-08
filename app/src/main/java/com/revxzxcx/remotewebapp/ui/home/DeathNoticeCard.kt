package com.revxzxcx.remotewebapp.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.revxzxcx.remotewebapp.core.notify.DeathCause
import com.revxzxcx.remotewebapp.core.notify.DeathNotice
import com.revxzxcx.remotewebapp.core.notify.deathActionLabel
import com.revxzxcx.remotewebapp.core.notify.deathBody
import com.revxzxcx.remotewebapp.core.notify.deathTitle

/** 测试用 tag：断言这张卡在不在。 */
internal const val DeathNoticeCardTag = "death-notice-card"

/**
 * 「上次是被系统杀掉的」提示卡（R056 / Q3）。
 *
 * ## 为什么放在首页内容区最上面
 * 用户点开 App 的第一个动作是看列表。如果把提示塞进设置页，那正是 Q3 里被排除的方案——
 * 一加用户大概率不会主动去找设置，结果就是「装了、没配、收不到、以为坏了」。
 *
 * 放在列表顶上，它挡住的只是第一条会话的一小部分，而用户放下手机再回来时，
 * 「我明明发过任务却没有通知」这个疑问正要冒出来，此刻解释最有效。
 *
 * ## 为什么是「一次性」的
 * 这条卡片的可见性由 [com.revxzxcx.remotewebapp.core.notify.DeathWatcher] 的
 * `pending` 决定，用户点掉之后同一次冷启动内不再出现；下次启动时基线已经推进，
 * 同一条历史不会再被挑出来。**不做「永久不再提示」开关**：被杀的场景每次都可能再发生，
 * 而用户真正该做的是去开保活，静音它只会让问题以「通知偶尔收不到」的形式长期存在。
 *
 * ## 两种 cause 的措辞为什么不同
 * - [DeathCause.SystemKilled]：这是用户该去改设置的那种，正文必须指向「四项保活」。
 * - [DeathCause.Stopped]：**分不出是谁干的**（`getSubReason()` 不是公开 API，
 *   用户划掉 / 系统 killBackground / force stop 都是 `REASON_USER_REQUESTED`）。
 *   所以措辞不指名道姓，正文把两种可能都摆出来，按钮仍指向保活设置——
 *   既然分不出来，就当「想让它别停」来处理，这比猜错得罪一半用户强。
 */
@Composable
internal fun DeathNoticeCard(
    notice: DeathNotice,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actionLabel = deathActionLabel(notice.cause)
    // 两类都用 tertiaryContainer：一加把它当「注意」而不是「错误」。
    // 用 errorContainer 会让用户以为 App 出故障了，而实际情况是系统在正常回收后台进程。
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = DeathNoticeCardTag },
    ) {
        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = deathTitle(notice.cause),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Text(
                text = deathBody(notice.cause),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (actionLabel.isNotEmpty()) {
                    Button(onClick = onAction) { Text(actionLabel) }
                }
                TextButton(onClick = onDismiss) { Text("先不管") }
            }
        }
    }
}
