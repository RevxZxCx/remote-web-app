package com.revxzxcx.remotewebapp.core.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 离线缓存数据库（决策 D04）。
 *
 * `exportSchema = false`：本库是**纯缓存**，唯一的迁移策略是
 * [RoomDatabase.Builder.fallbackToDestructiveMigration]（见 [open]）——
 * 换版本直接重建，不清也算不清（清掉的只是能重新拉回来的副本）。
 * 因此不值得维护 `schemas/` 目录与迁移脚本：那会让每次改字段都多一次无意义的仪式，
 * 而错误的迁移脚本反而会把「能重新拉回来」变成「必须重新拉回来还得先崩一次」。
 */
@Database(
    entities = [
        CachedWorkspace::class,
        CachedSession::class,
        CachedMessage::class,
        CachedMeta::class,
        CachedDraft::class,
    ],
    // 改实体**必须**同时改这个数字：Room 会把 schema 哈希写进 room_master_table，
    // 哈希对不上就抛 `IllegalStateException: Room cannot verify the data integrity`，
    // 而不是「自动帮你重建成新 schema」。R039 加 `drafts` 表时漏了这一步，
    // 结果 `DshCache.guard` 把打开失败的异常吃成 null，草稿写入静默失效——单测全绿、
    // 真机一次都没落库（见决策 D127）。
    version = 2,
    exportSchema = false,
)
abstract class DshDatabase : RoomDatabase() {

    abstract fun cacheDao(): CacheDao

    companion object {
        private const val NAME: String = "dsh-cache.db"

        /**
         * 打开（必要时创建）缓存库。
         *
         * 用 `fallbackToDestructiveMigration()` 而不是任何真实迁移：缓存里的每一行都能从
         * 服务端重新取回，为它写迁移是**用正确性风险换零收益**。
         */
        fun open(context: Context): DshDatabase =
            Room.databaseBuilder(context.applicationContext, DshDatabase::class.java, NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
