package io.noties.markwon.chatdemo

import androidx.multidex.MultiDexApplication
import io.noties.markwon.chatdemo.util.AppLog

/**
 * chat-demo 应用入口。
 *
 * <p>单 Activity（ChatActivity）+ ViewModel + Room 组合完成全部聊天功能，
 * 不依赖任何用户 / VIP / 云存储体系。
 */
class ChatDemoApp : MultiDexApplication() {

    override fun onCreate() {
        super.onCreate()
        // 恢复持久化的日志级别（构建类型默认：debug=D / release=W）
        AppLog.init(this)
        AppLog.i(AppLog.TAG_INIT, "ChatDemoApp onCreate, versionName=${BuildConfig.VERSION_NAME}")
    }
}