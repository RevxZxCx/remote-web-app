package com.revxzxcx.remotewebapp.core.cache

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 缓存库「改了实体但忘了改 version」的守门人（R039 真机事故的回归测试）。
 *
 * ## 事故经过
 *
 * R039 给 `DshDatabase` 加了 `CachedDraft` 实体（新表 `drafts`），但 `version` 仍是 1。
 * 单测全绿——`DshCacheTest` 用的是手写 `FakeDao`（决策 D21），根本不碰 Room。
 * 真机上 App 却一次都没把草稿写进库：Room 打开时把 `room_master_table` 里的
 * 身份哈希与自己按实体算出来的哈希比对，不一致就抛
 * `IllegalStateException: Room cannot verify the data integrity`；
 * `version` 没变，`fallbackToDestructiveMigration` 就不会触发重建。
 *
 * 更隐蔽的是：`DshCache.guard` 按契约把这类异常吃成 `null`（「缓存故障不崩 UI」），
 * 于是失败**完全静默**——没有崩溃、没有日志、输入框照样能打字，只是退出后一切归零。
 *
 * ## 这个测试怎么抓它
 *
 * 不走 `FakeDao`，而是**真的开一个文件数据库**，然后手工把库退化成「旧版本的库」：
 * 把 `user_version` 改回上一版、把 `room_master_table` 的身份哈希写坏。再打开一次：
 *
 * - `version` 已提升 → Room 认出版本变化，走 destructive migration 重建，`drafts` 表出现；
 * - `version` 忘了提升 → 版本相同 + 哈希不符 → 直接抛异常（用变异验证确认过会变红）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class DshDatabaseSchemaTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Robolectric 给每个测试独立的 files/databases 目录，但同一测试类内会复用，
        // 所以显式删掉，避免上一个用例留下的库把「首次创建」路径掩盖掉。
        context.getDatabasePath("dsh-cache.db").delete()
    }

    @After
    fun tearDown() {
        context.getDatabasePath("dsh-cache.db").delete()
    }

    @Test
    fun `实体变了而版本没变时缓存库会静默失效，版本提升后才自动重建`() = runBlocking {
        val owner = "owner-1"

        // ① 先正常建库并写一条草稿，确认新表在。
        val first = DshDatabase.open(context)
        first.cacheDao().putDraft(
            CachedDraft(ownerId = owner, sessionId = "s-1", text = "草稿", updatedAt = 1L),
        )
        first.close()
        assertNotNull(DshCache.create(DshDatabase.open(context).cacheDao()).readDraft(owner, "s-1"))

        // ② 把库伪装成「上一个版本」：版本号退一格 + 身份哈希写坏。
        //    这正是「加了实体但忘了改 version」在设备上的样子。
        degradeToPreviousVersion()

        // ③ 再打开：必须能重建，而且新表可用。
        val reopened = DshCache.create(DshDatabase.open(context).cacheDao())
        reopened.writeDraft(owner, "s-2", "下一个草稿")
        assertEquals("下一个草稿", reopened.readDraft(owner, "s-2"))
        // 旧数据在破坏性迁移里被丢掉了——这是本库的既定契约（全量缓存可重拉）。
        assertEquals(null, reopened.readDraft(owner, "s-1"))
    }

    /**
     * 把 `dsh-cache.db` 退回「旧 schema + 旧版本号」。
     *
     * `user_version` 就是 Room 的 `version`。身份哈希在 `room_master_table` 里，
     * 键名 `identity_hash`；写坏它模拟 schema 与代码不一致。
     */
    private fun degradeToPreviousVersion() {
        val path = context.getDatabasePath("dsh-cache.db").absolutePath
        val raw = android.database.sqlite.SQLiteDatabase.openDatabase(
            path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        raw.use { db ->
            db.execSQL("UPDATE room_master_table SET identity_hash = 'stale-hash-from-old-schema'")
            db.version = db.version - 1
        }
    }
}
