package io.noties.markwon.chatdemo

import androidx.multidex.MultiDexApplication
import io.noties.markwon.block.view.ImageBlockView
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
        // 注册 markwon-image 默认图片加载器：AI 回复中的 markdown 图片（独立行 ![](url) 与
        // 行内图片）真正加载显示，而非占位块；默认 loader 支持 data-uri / http(s)，
        // HTTP 走本模块已有的 okhttp 栈。切换 Glide 时换成 markwon-image-glide 的 loader 即可。
        ImageBlockView.setDefaultAsyncDrawableLoader(ImageBlockView.defaultMarkwonLoader())
        AppLog.i(AppLog.TAG_INIT, "ChatDemoApp onCreate, versionName=${BuildConfig.VERSION_NAME}")
    }
}