package com.choury.bridgemcp

import android.app.Application

// 能力处理器在服务上下文运行，统一从这里取全局 Context。
class BridgeApp : Application() {
    companion object {
        lateinit var instance: BridgeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
