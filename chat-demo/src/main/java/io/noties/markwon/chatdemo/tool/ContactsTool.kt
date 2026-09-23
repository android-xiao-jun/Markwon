package io.noties.markwon.chatdemo.tool

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * 联系人查询：按姓名/电话关键词搜索通讯录（READ_CONTACTS 运行时权限）
 *
 * 未授权时弹窗向用户说明用途并申请；仅读取姓名与电话，不修改通讯录。
 */
class ContactsTool : ChatTool {

    override val name: String = "query_contacts"

    override val description: String =
        "按姓名或电话号码关键词查询手机通讯录联系人，返回姓名、电话号码、号码类型（手机/住宅/工作）。需要联系人读取权限（会先向用户申请授权）。当用户要求查联系人、找某人的电话号码、搜索通讯录时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("keyword", JSONObject().apply {
                put("type", "string")
                put("description", "搜索关键词（姓名或号码片段，不区分大小写）；留空返回前 N 个联系人")
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "最多返回条数，默认 20，上限 50")
            })
        })
        put("required", JSONArray())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val keyword = args.optString("keyword").trim()
        val limit = args.optInt("limit", 20).coerceIn(1, 50)

        val granted = ToolPermissionManager.ensure(
            context, Manifest.permission.READ_CONTACTS, name,
            "搜索联系人姓名与电话号码（仅读取，不会修改、上传通讯录）"
        )
        if (!granted) {
            return "错误：用户未授权读取联系人（READ_CONTACTS），无法查询通讯录。请向用户说明用途后重试，或引导其在系统设置中开启。"
        }

        return try {
            query(context, keyword, limit)
        } catch (e: SecurityException) {
            "错误：读取联系人被系统拒绝（${e.message}），请确认权限已授予"
        } catch (e: Exception) {
            "错误：联系人查询失败（${e.message}）"
        }
    }

    private fun query(context: Context, keyword: String, limit: Int): String {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.TYPE
        )
        val selection: String?
        val selectionArgs: Array<String>?
        if (keyword.isEmpty()) {
            selection = null
            selectionArgs = null
        } else {
            // 姓名或号码匹配（号码同时匹配原样/去空格两种写法）
            selection = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?" +
                    " OR " + ContactsContract.CommonDataKinds.Phone.NUMBER + " LIKE ?"
            val like = "%$keyword%"
            selectionArgs = arrayOf(like, like)
        }

        val rows = mutableListOf<Triple<String, String, String>>()
        context.contentResolver.query(
            uri, projection, selection, selectionArgs,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            while (cursor.moveToNext() && rows.size < limit) {
                val name = cursor.getString(0) ?: "（无姓名）"
                val number = cursor.getString(1) ?: continue
                val type = typeText(cursor.getInt(2))
                rows.add(Triple(name, number, type))
            }
        }

        if (rows.isEmpty()) {
            return if (keyword.isEmpty()) "通讯录为空（未读到任何联系人）"
            else "通讯录中未找到与「$keyword」匹配的联系人"
        }

        return buildString {
            if (keyword.isEmpty()) {
                append("通讯录前 ").append(rows.size).append(" 位联系人：\n\n")
            } else {
                append("找到 ").append(rows.size).append(" 位与「").append(keyword).append("」匹配的联系人：\n\n")
            }
            append("| 姓名 | 电话 | 类型 |\n| --- | --- | --- |\n")
            rows.forEach { (name, number, type) ->
                append("| ").append(name)
                    .append(" | ").append(number)
                    .append(" | ").append(type)
                    .append(" |\n")
            }
            append("\n说明：同一联系人多个号码会显示多行；仅读取姓名与电话，未读取其他资料。")
        }
    }

    private fun typeText(type: Int): String = when (type) {
        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "手机"
        ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "住宅"
        ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "工作"
        ContactsContract.CommonDataKinds.Phone.TYPE_MAIN -> "主号码"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK -> "工作传真"
        ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "住宅传真"
        ContactsContract.CommonDataKinds.Phone.TYPE_OTHER -> "其他"
        else -> "未知"
    }
}
