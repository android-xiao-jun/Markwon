package io.noties.markwon.chatdemo.util

import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * 本地文件内容提取器（消息附件注入 / read_file 工具共用，DRY 单一出处）
 *
 * 按格式分派读取策略：
 * ```
 * 直接读取（UTF-8，非法序列回退 GB18030）  txt/md/json/html/csv/代码等文本文件
 * 文本层提取（PDFBox-Android）             pdf
 * OOXML 解析（zip + XML，零依赖）          docx / xlsx / pptx
 * 压缩包清单 + 内嵌文本提取（零依赖）       zip / jar / apk / epub
 * 不可解析（明确说明 + 替代建议）           旧版 Office（doc/xls/ppt）、rtf、其他二进制
 * ```
 *
 * 图片不走本解析器：由消息通道按 Base64 多模态处理（见 OpenAIMessageBuilder），
 * read_file 工具命中图片时返回说明（见 ReadFileTool）。
 *
 * 全部解析由调用方在 IO 线程执行；结果长度按 maxChars 截断保护上下文。
 */
object FileTextExtractor {

    // ==================== 结果模型 ====================

    sealed class Result {
        /** 提取成功；[truncated] 表示内容超过 maxChars 被截断 */
        data class Text(val content: String, val truncated: Boolean) : Result()

        /** 格式暂不支持解析，[reason] 为可直接展示给模型/用户的说明 */
        data class Unsupported(val reason: String) : Result()

        /** 属支持范围但读取失败（损坏/加密/IO 异常），[reason] 为失败原因 */
        data class Failed(val reason: String) : Result()
    }

    // ==================== 格式分类 ====================

    /** 可直接按文本读取的后缀（UTF-8，非法序列回退 GB18030） */
    val TEXT_EXTS = setOf(
        "txt", "md", "markdown", "json", "xml", "html", "htm", "xhtml", "svg", "css",
        "csv", "tsv", "log", "ini", "cfg", "conf", "properties", "yml", "yaml", "toml",
        "gradle", "sql", "kt", "kts", "java", "py", "js", "ts", "sh", "bat", "c", "cpp", "h", "hpp"
    )

    /** 图片后缀（文本模型无法读取内容；多模态通道经 Attachment.isImage() 走 Base64） */
    val IMAGE_EXTS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "heif")

    /** 压缩包类后缀（清单 + 内嵌文本提取） */
    private val ARCHIVE_EXTS = setOf("zip", "jar", "apk", "epub")

    /** 旧版 Office 二进制格式（OLE 复合文档，无法解析） */
    private val LEGACY_OFFICE_EXTS = setOf("doc", "xls", "ppt")

    // ==================== 解析上限 ====================

    /** PDF 最多提取页数（超长 PDF 只取前若干页，避免解析耗时与 token 爆炸） */
    private const val MAX_PDF_PAGES = 20

    /** 每个工作表最多解析行数 */
    private const val MAX_SHEET_ROWS = 300

    /** 最多解析工作表数量 */
    private const val MAX_SHEETS = 3

    /** 压缩包清单最多列出条目数 */
    private const val MAX_ARCHIVE_LIST = 100

    /** 压缩包内单个文本条目提取上限 */
    private const val MAX_TEXT_ENTRY_BYTES = 64 * 1024

    /** 压缩包内文本条目内容提取总量上限 */
    private const val MAX_ARCHIVE_TEXT_TOTAL = 128 * 1024

    /** 压缩包内最多提取文本条目数 */
    private const val MAX_ARCHIVE_TEXT_ENTRIES = 5

    /** pptx 幻灯片条目匹配（ppt/slides/slideN.xml） */
    private val SLIDE_ENTRY_REGEX = Regex("ppt/slides/slide\\d+\\.xml")

    // ==================== 主入口 ====================

    fun isTextExt(ext: String): Boolean = ext in TEXT_EXTS

    /** 取文件名后缀（小写，无点） */
    fun extOf(name: String): String = name.substringAfterLast('.', "").toLowerCase(Locale.US)

    /**
     * 提取文件文本内容。
     * 空结果统一转为 Unsupported（空文件/扫描件/结构异常说明），调用方无需再判空。
     */
    fun extract(file: File, maxChars: Int): Result {
        if (!file.exists()) return Result.Failed("文件不存在")
        if (file.isDirectory) return Result.Failed("该路径是目录不是文件")

        val ext = extOf(file.name)
        val startNs = System.nanoTime()
        val result = try {
            when {
                ext in TEXT_EXTS -> readPlainText(file, maxChars)
                ext == "pdf" -> extractPdf(file, maxChars)
                ext == "docx" -> extractDocx(file, maxChars)
                ext == "xlsx" -> extractXlsx(file, maxChars)
                ext == "pptx" -> extractPptx(file, maxChars)
                ext in ARCHIVE_EXTS -> extractArchive(file, maxChars)
                ext in IMAGE_EXTS ->
                    Result.Unsupported("图片文件无法以文本形式读取；如需识图请以图片附件发送并切换到支持视觉的模型")
                ext in LEGACY_OFFICE_EXTS ->
                    Result.Unsupported("旧版 Office 二进制格式（.$ext）不支持解析，请用 Office/WPS 另存为 .docx/.xlsx/.pptx 后重试")
                ext == "rtf" ->
                    Result.Unsupported("RTF 富文本格式不支持解析，建议另存为 .txt 或 .docx 后重试")
                else ->
                    Result.Unsupported("未知或二进制格式（${if (ext.isEmpty()) "无后缀" else ".$ext"}）不支持解析")
            }
        } catch (e: Exception) {
            AppLog.w(AppLog.TAG_PARSE, "extract error: ${file.name}, ${e.message}")
            Result.Failed("文件解析异常（${e.message}）")
        }

        AppLog.d(
            AppLog.TAG_PARSE,
            "extract: ${file.name}, ext=$ext, result=${result::class.java.simpleName}, ${AppLog.elapsedMs(startNs)}"
        )
        // 空结果统一提示（空文件 / 扫描件 PDF / 无文本幻灯片等）
        if (result is Result.Text && result.content.isBlank()) {
            return Result.Unsupported("未提取到文本内容（文件可能为空、为扫描件或结构异常）")
        }
        return result
    }

    // ==================== 文本直读 ====================

    private fun readPlainText(file: File, maxChars: Int): Result {
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) {
            return Result.Unsupported("文件包含二进制数据（可能是改过扩展名的非文本文件），无法按文本读取")
        }
        return clip(decodeText(bytes), maxChars)
    }

    /** UTF-8 严格解码失败时回退 GB18030（覆盖 GBK/GB2312，Windows 中文文本常见编码） */
    private fun decodeText(bytes: ByteArray): String = try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: Exception) {
        try {
            String(bytes, Charset.forName("GB18030"))
        } catch (e2: Exception) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }

    private fun clip(text: String, maxChars: Int): Result.Text {
        val normalized = text.replace("\r\n", "\n").replace('\r', '\n')
        return if (normalized.length > maxChars) {
            Result.Text(normalized.substring(0, maxChars), truncated = true)
        } else {
            Result.Text(normalized, truncated = false)
        }
    }

    // ==================== PDF（PDFBox-Android） ====================

    private fun extractPdf(file: File, maxChars: Int): Result {
        val doc = PDDocument.load(file)
        doc.use {
            if (it.isEncrypted) return Result.Failed("PDF 已加密（需要密码），无法提取文本")
            val pageCount = it.numberOfPages
            if (pageCount <= 0) return Result.Failed("PDF 无有效页面")
            val stripper = PDFTextStripper().apply {
                startPage = 1
                endPage = minOf(pageCount, MAX_PDF_PAGES)
            }
            var text = stripper.getText(it).trim()
            if (pageCount > MAX_PDF_PAGES) {
                text = "（PDF 共 $pageCount 页，仅提取前 $MAX_PDF_PAGES 页文本）\n$text"
            }
            return clip(text, maxChars)
        }
    }

    // ==================== docx / pptx（OOXML 段落文本） ====================

    private fun extractDocx(file: File, maxChars: Int): Result {
        ZipFile(file).use { zip ->
            val entry = zip.getEntry("word/document.xml")
                ?: return Result.Failed("docx 结构不完整（缺少 word/document.xml）")
            val sb = StringBuilder()
            zip.getInputStream(entry).use { collectParagraphs(it, sb) }
            return clip(sb.toString().trim(), maxChars)
        }
    }

    private fun extractPptx(file: File, maxChars: Int): Result {
        ZipFile(file).use { zip ->
            val slides = zipEntries(zip)
                .filter { SLIDE_ENTRY_REGEX.matches(it.name) }
                .sortedBy { entryIndex(it.name, "slide") }
            if (slides.isEmpty()) return Result.Failed("pptx 结构不完整（未找到幻灯片）")
            val sb = StringBuilder()
            for ((i, slide) in slides.withIndex()) {
                sb.append("[第 ").append(i + 1).append(" 页]\n")
                zip.getInputStream(slide).use { collectParagraphs(it, sb) }
            }
            return clip(sb.toString().trim(), maxChars)
        }
    }

    /**
     * 从 OOXML 段落流提取纯文本（docx / pptx 通用）：
     * `<w:t>` / `<a:t>` 累计文本；`<w:tab>`→Tab；`<w:br>`→换行；`<w:p>` / `<a:p>` 段落结束→换行
     */
    private fun collectParagraphs(input: InputStream, sb: StringBuilder) {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "tab" -> sb.append('\t')
                    "br" -> sb.append('\n')
                }
                XmlPullParser.TEXT -> if (localName(parser.name) == "t") sb.append(parser.text)
                XmlPullParser.END_TAG -> if (localName(parser.name) == "p") sb.append('\n')
            }
            event = parser.next()
        }
    }

    // ==================== xlsx（共享字符串 + 工作表单元格） ====================

    private fun extractXlsx(file: File, maxChars: Int): Result {
        ZipFile(file).use { zip ->
            // 1. 共享字符串表（索引 → 文本）
            val shared = ArrayList<String>()
            zip.getEntry("xl/sharedStrings.xml")?.let { entry ->
                zip.getInputStream(entry).use { parseSharedStrings(it, shared) }
            }
            // 2. 工作表（按 sheetN 数字序，最多前 MAX_SHEETS 个）
            val sheets = zipEntries(zip)
                .filter { it.name.startsWith("xl/worksheets/sheet") && it.name.endsWith(".xml") }
                .sortedBy { entryIndex(it.name, "sheet") }
            if (sheets.isEmpty()) return Result.Failed("xlsx 结构不完整（未找到工作表）")

            val sb = StringBuilder()
            var rowsLeft = MAX_SHEET_ROWS
            var parsed = 0
            for ((i, sheet) in sheets.take(MAX_SHEETS).withIndex()) {
                if (rowsLeft <= 0) break
                sb.append("[工作表 ").append(i + 1).append("：").append(sheet.name.substringAfterLast('/')).append("]\n")
                val stats = zip.getInputStream(sheet).use { parseSheet(it, shared, sb, rowsLeft) }
                rowsLeft -= stats.rows
                parsed++
                if (stats.truncated) sb.append("……（本表超过行数上限，已截断）\n")
            }
            if (sheets.size > parsed) {
                sb.append("……（共 ${sheets.size} 个工作表，仅解析前 $parsed 个）\n")
            }
            return clip(sb.toString().trim(), maxChars)
        }
    }

    /** `<si>` 集合 → 共享字符串列表（`<t>` 与富文本 run `<r><t>` 均累计） */
    private fun parseSharedStrings(input: InputStream, out: MutableList<String>) {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var current: StringBuilder? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> if (localName(parser.name) == "si") current = StringBuilder()
                XmlPullParser.TEXT ->
                    if (current != null && localName(parser.name) == "t") current.append(parser.text)
                XmlPullParser.END_TAG -> if (localName(parser.name) == "si") {
                    out.add(current?.toString() ?: "")
                    current = null
                }
            }
            event = parser.next()
        }
    }

    /** 工作表解析结果：行数 + 是否因行数上限截断 */
    private data class SheetStats(val rows: Int, val truncated: Boolean)

    /**
     * 解析单个工作表：行 → 换行，单元格 → Tab 分隔。shared 为共享字符串表；
     * `t="s"` 取共享字符串索引，`t="inlineStr"` 取 `<is><t>`，其余取 `<v>` 原值。
     */
    private fun parseSheet(input: InputStream, shared: List<String>, sb: StringBuilder, maxRows: Int): SheetStats {
        val parser = Xml.newPullParser()
        parser.setInput(input, null)
        var rowCount = 0
        var inCell = false
        var cellType: String? = null
        var cellValue = StringBuilder()   // <v> 值
        var inlineValue = StringBuilder() // <is><t> 值
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (localName(parser.name)) {
                    "row" -> {
                        if (rowCount >= maxRows) return SheetStats(rowCount, true)
                    }
                    "c" -> {
                        inCell = true
                        cellType = attr(parser, "t")
                        cellValue = StringBuilder()
                        inlineValue = StringBuilder()
                    }
                }
                XmlPullParser.TEXT -> if (inCell) when (localName(parser.name)) {
                    "v" -> cellValue.append(parser.text)
                    "t" -> inlineValue.append(parser.text)
                }
                XmlPullParser.END_TAG -> when (localName(parser.name)) {
                    "c" -> {
                        val value = when (cellType) {
                            "s" -> shared.getOrNull(cellValue.toString().trim().toIntOrNull() ?: -1) ?: ""
                            "inlineStr" -> inlineValue.toString()
                            else -> cellValue.toString()
                        }
                        sb.append(value.replace('\n', ' ')).append('\t')
                        inCell = false
                    }
                    "row" -> {
                        sb.append('\n')
                        rowCount++
                    }
                }
            }
            event = parser.next()
        }
        return SheetStats(rowCount, false)
    }

    // ==================== 压缩包（清单 + 内嵌文本） ====================

    private fun extractArchive(file: File, maxChars: Int): Result {
        ZipFile(file).use { zip ->
            val entries = zipEntries(zip)
            if (entries.isEmpty()) return Result.Failed("压缩包为空或结构异常")

            val sb = StringBuilder()
            sb.append("压缩包（共 ").append(entries.size).append(" 个项目）内容清单：\n")
            entries.take(MAX_ARCHIVE_LIST).forEach { e ->
                sb.append(if (e.isDirectory) "📁 " else "📄 ").append(e.name)
                if (!e.isDirectory) sb.append("（").append(e.size).append(" B）")
                sb.append('\n')
            }
            if (entries.size > MAX_ARCHIVE_LIST) {
                sb.append("……（其余 ").append(entries.size - MAX_ARCHIVE_LIST).append(" 项省略）\n")
            }

            // 小型文本条目顺带提取内容（总量可控才展开）
            val textEntries = entries.filter {
                !it.isDirectory && extOf(it.name) in TEXT_EXTS && it.size in 1..MAX_TEXT_ENTRY_BYTES.toLong()
            }
            when {
                textEntries.isEmpty() -> sb.append("\n（内嵌文件中无可直接读取的文本条目；如需读取请先解压到应用工作区）\n")
                textEntries.fold(0L) { acc, e -> acc + e.size } > MAX_ARCHIVE_TEXT_TOTAL ->
                    sb.append("\n（内嵌文本条目超过 ${MAX_ARCHIVE_TEXT_TOTAL / 1024} KB，未展开内容；如需读取请先解压）\n")
                else -> {
                    sb.append("\n— 内嵌文本文件内容 —\n")
                    textEntries.take(MAX_ARCHIVE_TEXT_ENTRIES).forEach { e ->
                        sb.append("【").append(e.name).append("】\n")
                        val text = try {
                            zip.getInputStream(e).use { decodeText(readStreamLimited(it, MAX_TEXT_ENTRY_BYTES)) }
                        } catch (ex: Exception) {
                            "（读取失败：${ex.message}）"
                        }
                        sb.append(text.trim()).append('\n')
                    }
                    if (textEntries.size > MAX_ARCHIVE_TEXT_ENTRIES) sb.append("……（其余文本条目省略）\n")
                }
            }
            return clip(sb.toString().trim(), maxChars)
        }
    }

    // ==================== 公共小工具 ====================

    private fun zipEntries(zip: ZipFile): List<ZipEntry> {
        val list = mutableListOf<ZipEntry>()
        val en = zip.entries()
        while (en.hasMoreElements()) list.add(en.nextElement())
        return list
    }

    /** 读流最多 limit 字节（压缩包内条目 size 不可信，做硬限制防解压炸弹） */
    private fun readStreamLimited(input: InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val n = input.read(buf, 0, minOf(buf.size, limit - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    /** 取标签/属性 local name（兼容 xml pull parser 开启或关闭命名空间处理两种模式） */
    private fun localName(qname: String?): String = qname?.substringAfterLast(':') ?: ""

    /** 按 local name 取属性值（兼容解析模式差异，遍历比对） */
    private fun attr(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (localName(parser.getAttributeName(i)) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    /** "xl/worksheets/sheet12.xml" → 12（按数字排序，避免 sheet10 排在 sheet2 前） */
    private fun entryIndex(entryName: String, marker: String): Int =
        entryName.substringAfterLast(marker).substringBefore('.').toIntOrNull() ?: Int.MAX_VALUE
}