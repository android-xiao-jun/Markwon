package io.noties.markwon.chatdemo.tool

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import org.json.JSONObject

/** 电池信息查询：电量/充电状态/温度/电压 */
class BatteryInfoTool : ChatTool {

    override val name: String = "query_battery_info"

    override val description: String = "查询设备电池信息，包括电量百分比、是否充电、充电方式、电池温度、电压等。当用户询问电量、充电、电池时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val intent = context.registerReceiver(
            null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return "无法获取电池信息"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10.0
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val statusText = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
            else -> "未知"
        }
        return """
| 项目 | 值 |
| --- | --- |
| 电量 | ${if (percent >= 0) "$percent%" else "未知"} |
| 状态 | $statusText |
| 温度 | ${"%.1f".format(temperature)} ℃ |
| 电压 | ${if (voltage > 0) "${voltage / 1000.0} V" else "未知"} |
""".trimIndent()
    }
}