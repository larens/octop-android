package com.octopmobile.app

import android.app.Application
import com.octopmobile.app.data.AppContainer
import com.octopmobile.app.data.mobile.MobileSyncWorker

/**
 * 原生客户端的 Application：装配全局依赖（DB / 配置 / HTTP），供 Compose 层取用。
 *
 * 依赖注入是手写的 [AppContainer]：这个规模的项目引入 Hilt 只会多一层需要调试的生成代码。
 */
class OctopApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MobileSyncWorker.schedule(this)
    }
}
