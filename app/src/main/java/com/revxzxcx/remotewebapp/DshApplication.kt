package com.revxzxcx.remotewebapp

import android.app.Application
import com.revxzxcx.remotewebapp.core.AppContainer

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
