package io.noties.markwon.chatdemo.tool

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.WriterException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 生成二维码：把文本/链接编码为二维码图片并保存到本地
 *
 * 输出到应用工作区 `qrcodes/qr_时间戳.png`（无需权限）；也可指定路径写公共存储
 * （需存储写入权限，规则与 write_file 一致）。
 * 结果末尾附带 [ToolImageProtocol] 图片标记，聊天页据此内联展示二维码并支持保存相册。
 */
class GenerateQrCodeTool : ChatTool {

    override val name: String = "generate_qr_code"

    override val description: String =
        "把文本/链接等内容生成为二维码图片并保存到手机本地，返回图片保存路径。当用户要求「生成二维码」「把链接/文本/联系方式做成二维码」时使用。" +
            "默认输出到应用工作区 qrcodes/ 目录（无需权限）；生成后聊天页会内联展示该图片，用户可保存到相册。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "二维码承载的内容（链接、文本、联系方式等），最长 2000 字符")
            })
            put("size", JSONObject().apply {
                put("type", "integer")
                put("description", "图片边长（像素），默认 600，范围 128-2048")
            })
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "可选：输出图片路径；默认工作区 qrcodes/qr_时间戳.png，一般无需指定")
            })
        })
        put("required", JSONArray().put("content"))
    }

    companion object {
        private const val DEFAULT_SIZE = 600
        private const val MIN_SIZE = 128
        private const val MAX_SIZE = 2048
        private const val MAX_CONTENT_CHARS = 2000
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val content = args.optString("content").trim()
        if (content.isEmpty()) return "错误：缺少参数 content（二维码内容）"
        if (content.length > MAX_CONTENT_CHARS) {
            return "错误：内容过长（${content.length} 字符，上限 $MAX_CONTENT_CHARS），二维码容量有限，请精简内容"
        }
        val size = args.optInt("size", DEFAULT_SIZE).coerceIn(MIN_SIZE, MAX_SIZE)
        val rawPath = args.optString("path").trim()
        val file = if (rawPath.isEmpty()) {
            defaultOutputFile(context)
        } else {
            ToolPaths.resolve(context, rawPath)
        }

        // 沙箱外公共路径 → 先申请存储写入权限（与 write_file 规则一致）
        if (!ToolPaths.isSandbox(context, file)) {
            if (!ToolPaths.legacyStorageUsable()) return ToolPaths.legacyStorageBlockedTip()
            val granted = ToolPermissionManager.ensure(
                context, Manifest.permission.WRITE_EXTERNAL_STORAGE, name,
                "把生成的二维码图片保存到公共存储：${file.absolutePath}（仅写入该图片）"
            )
            if (!granted) {
                return "错误：用户未授权存储写入权限，无法保存到 ${file.absolutePath}。可改为不传 path 使用工作区默认路径（无需权限）。"
            }
        }

        return try {
            val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, encodeHints())
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val row = y * size
                for (x in 0 until size) {
                    pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
                }
            }
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            file.parentFile?.takeIf { !it.exists() }?.mkdirs()
            file.outputStream().use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
            bitmap.recycle()
            buildString {
                append("已生成二维码图片：`").append(file.absolutePath).append("`（")
                append(size).append("×").append(size).append(" 像素，PNG，").append(file.length()).append(" 字节）\n")
                append("内容：").append(content.take(80))
                if (content.length > 80) append("…")
                append("\n聊天页已内联展示该图片，用户可点击「保存到相册」保存。\n\n")
                append(ToolImageProtocol.mark(file.absolutePath))
            }
        } catch (e: WriterException) {
            "错误：内容无法生成二维码（${e.message}），可能是内容过长，请精简后重试"
        } catch (e: Exception) {
            "错误：二维码生成或写入失败（${e.message}）"
        }
    }

    /** 默认输出文件：工作区 qrcodes/qr_时间戳.png（同秒重复则加序号） */
    private fun defaultOutputFile(context: Context): File {
        val dir = File(ToolPaths.workspaceRoot(context), "qrcodes")
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        var file = File(dir, "qr_$stamp.png")
        var index = 1
        while (file.exists()) {
            file = File(dir, "qr_${stamp}_$index.png")
            index++
        }
        return file
    }

    /** 编码参数：UTF-8 中文兼容 + 二维码四周留 2 模块静默区 */
    private fun encodeHints(): Map<EncodeHintType, Any> {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.CHARACTER_SET] = "UTF-8"
        hints[EncodeHintType.MARGIN] = 2
        return hints
    }
}

/**
 * 识别二维码：解码图片中的二维码（QR Code），返回其内容
 *
 * 图片来源优先级：
 * 1. 参数 path 指定的图片文件（工作区相对路径无需权限；公共存储需读取权限）；
 * 2. 未指定 path 时兜底识别用户最近一条消息中发送的图片（见 [ToolSessionState]）。
 * 全程本地解码，不上传图片。
 */
class DecodeQrCodeTool : ChatTool {

    override val name: String = "decode_qr_code"

    override val description: String =
        "识别图片中的二维码并返回其内容（文本/链接）。path 可指定本地图片文件路径；不传时自动识别用户最近发送的图片。" +
            "当用户要求「识别这个二维码」「看看这张图里的二维码是什么」「解析二维码」时使用。"

    override val parametersSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "可选：待识别图片的文件路径；不传则识别用户最近发送的图片")
            })
        })
    }

    companion object {
        /** 解码前的最大边长采样限制（解码不需要原图分辨率，避免大图 OOM） */
        private const val MAX_DECODE_EDGE = 2000
    }

    override suspend fun execute(context: Context, args: JSONObject): String {
        val rawPath = args.optString("path").trim()
        val files: List<File> = if (rawPath.isNotEmpty()) {
            listOf(ToolPaths.resolve(context, rawPath))
        } else {
            ToolSessionState.latestUserImagePaths.map { File(it) }
        }
        if (files.isEmpty()) {
            return "错误：没有可识别的图片。请提示用户在聊天中发送二维码图片，或提供图片文件路径（path）"
        }

        var tried = 0
        for (file in files) {
            if (!file.isFile) continue
            // 沙箱外公共路径 → 先申请存储读取权限（与 read_file 规则一致）
            if (!ToolPaths.isSandbox(context, file)) {
                if (!ToolPaths.legacyStorageUsable()) return ToolPaths.legacyStorageBlockedTip()
                val granted = ToolPermissionManager.ensure(
                    context, Manifest.permission.READ_EXTERNAL_STORAGE, name,
                    "读取公共存储中的图片用于二维码识别：${file.absolutePath}（仅本地解码，不上传）"
                )
                if (!granted) return "错误：用户未授权存储读取权限，无法读取 ${file.absolutePath}"
            }
            tried++
            val text = decodeQr(file)
            if (text != null) {
                return buildString {
                    append("识别成功：图片 `").append(file.absolutePath).append("` 中的二维码内容如下\n\n```\n")
                    append(text)
                    append("\n```")
                }
            }
        }

        if (tried == 0) return "错误：图片文件不存在或不可读（${files.first().absolutePath}）"
        return "未识别到二维码：已尝试 $tried 张图片，均未检测到可解析的二维码。请提示用户确认图片清晰、包含完整二维码后重试。"
    }

    /** 解码单张图片：正常解码失败时整图反色再试一次（兼容深色底反色二维码） */
    private fun decodeQr(file: File): String? {
        val bitmap = decodeSampled(file) ?: return null
        try {
            val width = bitmap.width
            val height = bitmap.height
            if (width <= 0 || height <= 0) return null
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            decodePixels(pixels, width, height)?.let { return it }
            for (i in pixels.indices) {
                val color = pixels[i]
                pixels[i] = Color.argb(
                    Color.alpha(color),
                    255 - Color.red(color),
                    255 - Color.green(color),
                    255 - Color.blue(color)
                )
            }
            return decodePixels(pixels, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodePixels(pixels: IntArray, width: Int, height: Int): String? = try {
        val source = RGBLuminanceSource(width, height, pixels)
        val binary = BinaryBitmap(HybridBinarizer(source))
        MultiFormatReader().decode(binary, decodeHints()).text?.takeIf { it.isNotEmpty() }
    } catch (e: NotFoundException) {
        null
    } catch (e: Exception) {
        null
    }

    /** 采样解码：限制最大边长（同 FileHelper.decodeThumb 的采样思路） */
    private fun decodeSampled(file: File): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_DECODE_EDGE ||
            bounds.outHeight / (sample * 2) >= MAX_DECODE_EDGE
        ) {
            sample *= 2
        }
        BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    } catch (e: Exception) {
        null
    }

    /** 解码参数：UTF-8 中文内容兼容 + 复杂图片尽力尝试 */
    private fun decodeHints(): Map<DecodeHintType, Any> {
        val hints = HashMap<DecodeHintType, Any>()
        hints[DecodeHintType.CHARACTER_SET] = "UTF-8"
        hints[DecodeHintType.TRY_HARDER] = true
        return hints
    }
}