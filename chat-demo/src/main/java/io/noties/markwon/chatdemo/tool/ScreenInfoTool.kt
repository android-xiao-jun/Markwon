package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.graphics.Point
import android.util.DisplayMetrics
import android.view.WindowManager
import org.json.JSONObject

/** 屏幕信息查询：分辨率/密度/物理尺寸 */
class ScreenInfoTool : ChatTool {

    override val name: String = "query_screen_info"

    override val description: String = "查询当前设备屏幕信息，包括分辨率（宽高像素）、屏幕密度（dpi）、逻辑密度、刷新率等。当用户询问屏幕分辨率、屏幕大小、像素密度时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        // getRealSize/getRealMetrics 需 API 17，低版本回退 getSize/displayMetrics
        var width = 0
        var height = 0
        var density = 0f
        var densityDpi = 0
        if (android.os.Build.VERSION.SDK_INT >= 17) {
            val realSize = Point()
            display.getRealSize(realSize)
            width = realSize.x
            height = realSize.y
            val metrics = DisplayMetrics()
            display.getRealMetrics(metrics)
            density = metrics.density
            densityDpi = metrics.densityDpi
        } else {
            @Suppress("DEPRECATION")
            val size = Point()
            @Suppress("DEPRECATION")
            display.getSize(size)
            width = size.x
            height = size.y
            @Suppress("DEPRECATION")
            val metrics = context.resources.displayMetrics
            densityDpi = metrics.densityDpi
            density = metrics.density
        }
        return """
| 项目 | 值 |
| --- | --- |
| 分辨率 | ${width} × ${height} px |
| 密度 | ${densityDpi} dpi |
| 逻辑密度 | ${density} |
""".trimIndent()
    }
}