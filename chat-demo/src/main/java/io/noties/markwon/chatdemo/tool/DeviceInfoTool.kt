package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.os.Build
import org.json.JSONObject

/** 设备信息查询：型号/品牌/系统/架构等 */
class DeviceInfoTool : ChatTool {

    override val name: String = "query_device_info"

    override val description: String = "查询当前安卓设备的硬件与系统信息，包括型号、品牌、系统版本、SDK 版本、CPU 架构等。当用户询问设备型号、手机信息、系统版本、Android 版本时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val abiList = if (Build.VERSION.SDK_INT >= 21) {
            Build.SUPPORTED_ABIS.joinToString(", ")
        } else {
            Build.CPU_ABI
        }
        return """
| 项目 | 值 |
| --- | --- |
| 品牌 | ${Build.BRAND} |
| 型号 | ${Build.MODEL} |
| 设备 | ${Build.DEVICE} |
| 产品 | ${Build.PRODUCT} |
| Android 版本 | ${Build.VERSION.RELEASE} |
| SDK 版本 | ${Build.VERSION.SDK_INT} |
| 构建 ID | ${Build.DISPLAY} |
| CPU 架构 | $abiList |
| 硬件 | ${Build.HARDWARE} |
| 制造商 | ${Build.MANUFACTURER} |
""".trimIndent()
    }
}