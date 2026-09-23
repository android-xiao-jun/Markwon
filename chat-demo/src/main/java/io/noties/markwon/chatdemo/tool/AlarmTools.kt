package io.noties.markwon.chatdemo.tool

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 设置闹钟：通过系统时钟应用创建闹钟（AlarmClock 协议）
 *
 * SET_ALARM 为普通权限（安装时授予）；EXTRA_SKIP_UI 让时钟应用跳过确认直接创建，
 * 设备不支持时回退为打开时钟应用的闹钟设置界面由用户确认。
 */
class SetAlarmTool : ChatTool {

    override val name: String = "set_alarm"

    override val description: String =
        "在系统时钟应用中创建闹钟（不会自动拨打电话等，仅设置闹钟）。当用户要求叫醒、设置闹钟、某点提醒时使用，例如「明早 7 点叫我起床」。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("hour", JSONObject().apply {
                put("type", "integer")
                put("description", "小时（24 小时制 0-23）")
            })
            put("minute", JSONObject().apply {
                put("type", "integer")
                put("description", "分钟（0-59），默认 0")
            })
            put("label", JSONObject().apply {
                put("type", "string")
                put("description", "闹钟标签/备注，如「起床」「开会」")
            })
            put("days", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().put("type", "string"))
                put("description", "重复响铃的星期（英文缩写小写）：mon/tue/wed/thu/fri/sat/sun，如 [\"mon\",\"tue\",\"wed\",\"thu\",\"fri\"] 表示工作日；不传则为一次性闹钟")
            })
            put("skip_ui", JSONObject().apply {
                put("type", "boolean")
                put("description", "默认 true 直接创建；false 时打开时钟应用由用户手动确认")
            })
        })
        put("required", JSONArray().put("hour"))
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour !in 0..23) return "错误：参数 hour 取值需为 0-23（24 小时制）"
        if (minute !in 0..59) return "错误：参数 minute 取值需为 0-59"

        val label = args.optString("label").trim()
        val skipUi = args.optBoolean("skip_ui", true)
        val days = parseDays(args.optJSONArray("days"))

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // 工具拿到的是 Application context
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            putExtra(AlarmClock.EXTRA_VIBRATE, true)
            days?.let { putExtra(AlarmClock.EXTRA_DAYS, ArrayList(it)) }
        }

        return try {
            context.startActivity(intent)
            val timeText = "%02d:%02d".format(hour, minute)
            buildString {
                append("已请求系统时钟创建闹钟：**").append(timeText).append("**")
                if (label.isNotEmpty()) append("（").append(label).append("）")
                if (days != null) append("，重复：").append(daysText(days))
                if (!skipUi) append("；已打开时钟应用，请用户确认保存")
                append("。")
            }
        } catch (e: ActivityNotFoundException) {
            "错误：设备未找到支持闹钟的系统时钟应用"
        }
    }

    /** 星期缩写 → Calendar 常量；null 表示一次性闹钟 */
    private fun parseDays(array: JSONArray?): ArrayList<Int>? {
        if (array == null || array.length() == 0) return null
        val map = mapOf(
            "mon" to Calendar.MONDAY, "monday" to Calendar.MONDAY, "周一" to Calendar.MONDAY,
            "tue" to Calendar.TUESDAY, "tuesday" to Calendar.TUESDAY, "周二" to Calendar.TUESDAY,
            "wed" to Calendar.WEDNESDAY, "wednesday" to Calendar.WEDNESDAY, "周三" to Calendar.WEDNESDAY,
            "thu" to Calendar.THURSDAY, "thursday" to Calendar.THURSDAY, "周四" to Calendar.THURSDAY,
            "fri" to Calendar.FRIDAY, "friday" to Calendar.FRIDAY, "周五" to Calendar.FRIDAY,
            "sat" to Calendar.SATURDAY, "saturday" to Calendar.SATURDAY, "周六" to Calendar.SATURDAY,
            "sun" to Calendar.SUNDAY, "sunday" to Calendar.SUNDAY, "周日" to Calendar.SUNDAY
        )
        val result = ArrayList<Int>()
        for (i in 0 until array.length()) {
            map[array.optString(i).trim().toLowerCase(java.util.Locale.US)]?.let { result.add(it) }
        }
        return if (result.isEmpty()) null else ArrayList(result.distinct())
    }

    private fun daysText(days: List<Int>): String {
        val names = mapOf(
            Calendar.MONDAY to "周一", Calendar.TUESDAY to "周二", Calendar.WEDNESDAY to "周三",
            Calendar.THURSDAY to "周四", Calendar.FRIDAY to "周五", Calendar.SATURDAY to "周六",
            Calendar.SUNDAY to "周日"
        )
        val order = listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        return order.filter { it in days }.joinToString("、") { names[it].orEmpty() }
    }
}

/**
 * 设置倒计时：通过系统时钟应用启动定时器（AlarmClock 协议）
 */
class SetTimerTool : ChatTool {

    override val name: String = "set_timer"

    override val description: String =
        "在系统时钟应用中启动倒计时定时器。当用户要求「N 分钟后提醒我」「定时 X 分钟」时使用，例如泡面计时 5 分钟。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("seconds", JSONObject().apply {
                put("type", "integer")
                put("description", "倒计时总秒数（如 5 分钟传 300）")
            })
            put("label", JSONObject().apply {
                put("type", "string")
                put("description", "定时器标签/备注")
            })
            put("skip_ui", JSONObject().apply {
                put("type", "boolean")
                put("description", "默认 true 直接启动；false 时打开时钟应用由用户手动确认")
            })
        })
        put("required", JSONArray().put("seconds"))
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val seconds = args.optInt("seconds", -1)
        if (seconds <= 0 || seconds > 24 * 3600) return "错误：参数 seconds 需为 1~86400（秒）"
        val label = args.optString("label").trim()
        val skipUi = args.optBoolean("skip_ui", true)

        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
        }
        return try {
            context.startActivity(intent)
            val duration = if (seconds >= 60) "${seconds / 60} 分 ${seconds % 60} 秒" else "$seconds 秒"
            buildString {
                append("已请求系统时钟启动定时器：**").append(duration.trim()).append("**")
                if (label.isNotEmpty()) append("（").append(label).append("）")
                if (!skipUi) append("；已打开时钟应用，请用户确认启动")
                append("。")
            }
        } catch (e: ActivityNotFoundException) {
            "错误：设备未找到支持定时器的系统时钟应用"
        }
    }
}

/**
 * 查询下次闹钟：读取系统下一次即将响铃的闹钟（无需权限）
 */
class QueryNextAlarmTool : ChatTool {

    override val name: String = "query_next_alarm"

    override val description: String =
        "查询系统下一次即将响铃的闹钟时间。当用户询问「我最近有什么闹钟」「明早闹钟是几点」时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        if (Build.VERSION.SDK_INT < 21) return "当前系统版本过低，不支持查询闹钟"
        val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // Kotlin 1.4 属性合成对该 API 失效，显式调用 getter
        val info = am.nextAlarmClock
            ?: return "当前没有已设置的闹钟。"
        val format = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm (EEEE)", java.util.Locale.CHINA)
        return "下一次闹钟：**${format.format(java.util.Date(info.triggerTime))}**（来自系统时钟应用）"
    }
}
