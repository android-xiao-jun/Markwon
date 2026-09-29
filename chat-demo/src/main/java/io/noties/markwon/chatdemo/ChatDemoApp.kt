package io.noties.markwon.chatdemo

import androidx.multidex.MultiDexApplication
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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
        // 初始化 PDFBox 资源加载器：read_file 工具与消息附件的 PDF 文本提取依赖，
        // 须在任何 PDDocument.load 之前调用（幂等，仅首次复制字体等资源）
        PDFBoxResourceLoader.init(applicationContext)
        AppLog.i(AppLog.TAG_INIT, "ChatDemoApp onCreate, versionName=${BuildConfig.VERSION_NAME}")
    }
}