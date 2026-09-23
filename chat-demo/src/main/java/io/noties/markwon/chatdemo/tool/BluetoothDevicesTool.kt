package io.noties.markwon.chatdemo.tool

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * 蓝牙设备列表：查询本机已配对（绑定）的蓝牙设备
 *
 * 权限：
 * - Android 12（API 31）+：BLUETOOTH_CONNECT 运行时权限（弹窗向用户申请）；
 * - Android 11 及以下：BLUETOOTH 普通权限（安装时已授予，无需申请）。
 * 仅读取配对列表，不发起连接、不传输数据。
 */
class BluetoothDevicesTool : ChatTool {

    override val name: String = "query_bluetooth_devices"

    override val description: String =
        "查询本机已配对（绑定）的蓝牙设备列表，包括设备名称、MAC 地址、设备类型（耳机/手表/手机等）。当用户询问蓝牙设备、已配对的设备、连过哪些蓝牙耳机/手表时使用。仅读取配对记录，不会连接设备。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        // API 31+ 需运行时 BLUETOOTH_CONNECT（compileSdk 29 无常量，用字符串字面量）
        if (Build.VERSION.SDK_INT >= 31) {
            val granted = ToolPermissionManager.ensure(
                context, "android.permission.BLUETOOTH_CONNECT", name,
                "读取已配对的蓝牙设备列表（仅查看配对记录，不连接设备、不传输数据）"
            )
            if (!granted) {
                return "错误：用户未授权蓝牙权限（BLUETOOTH_CONNECT），无法读取已配对设备列表。请向用户说明用途后重试。"
            }
        }

        @Suppress("DEPRECATION") // API 31 起推荐 BluetoothManager.getAdapter，为兼容 compileSdk 29 使用默认适配器
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return "本机不支持蓝牙（未找到蓝牙适配器）"

        val stateText = try {
            if (adapter.isEnabled) "已开启" else "已关闭（仍可读取历史配对记录）"
        } catch (e: SecurityException) {
            "未知（无权限读取蓝牙状态）"
        }

        val devices: Set<BluetoothDevice> = try {
            adapter.bondedDevices ?: emptySet()
        } catch (e: SecurityException) {
            return "错误：读取蓝牙配对列表被系统拒绝（${e.message}），请确认已授予蓝牙权限"
        }

        if (devices.isEmpty()) {
            return "蓝牙状态：$stateText\n\n当前没有已配对的蓝牙设备。"
        }

        val sb = StringBuilder()
        sb.append("蓝牙状态：").append(stateText)
        sb.append("\n\n已配对蓝牙设备共 ").append(devices.size).append(" 个：\n\n")
        sb.append("| # | 设备名称 | MAC 地址 | 类型 |\n| --- | --- | --- | --- |\n")
        devices.sortedBy { it.address }.forEachIndexed { index, device ->
            val deviceName = try {
                device.name ?: "（未命名）"
            } catch (e: SecurityException) {
                "（无权限读取名称）"
            }
            sb.append("| ").append(index + 1)
                .append(" | ").append(deviceName)
                .append(" | `").append(device.address).append("`")
                .append(" | ").append(typeText(device))
                .append(" |\n")
        }
        return sb.toString().trim()
    }

    /** 设备类型映射（type 需 API 18+，低版本显示未知） */
    private fun typeText(device: BluetoothDevice): String {
        if (Build.VERSION.SDK_INT < 18) return "未知"
        return when (device.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙"
            BluetoothDevice.DEVICE_TYPE_LE -> "低功耗(BLE)"
            BluetoothDevice.DEVICE_TYPE_DUAL -> "双模"
            BluetoothDevice.DEVICE_TYPE_UNKNOWN -> "未知"
            else -> "未知"
        }
    }
}
