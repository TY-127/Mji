package com.moon.aiphone
import android.content.Context
import android.net.Uri
import java.io.File

object BookParser {

    const val CHUNK_SIZE = 3000

    data class Chunk(
        val index: Int,
        val title: String,
        val content: String,
        val startPos: Int
    )

    // 章节标题正则
    private val chapterPatterns = listOf(
        Regex("""^第[零一二三四五六七八九十百千\d]+[章节回集部卷].*"""),
        Regex("""^Chapter\s*\d+.*""", RegexOption.IGNORE_CASE),
        Regex("""^\d+[\.、．]\s*.+"""),
        Regex("""^【.{1,20}】.*""")
    )

    // 导入txt文件，保存到app内部存储，返回文件路径
    fun importTxt(context: Context, uri: Uri): Triple<String, String, Int> {
        val fileName = getFileName(context, uri)
        val booksDir = File(context.filesDir, "books")
        booksDir.mkdirs()
        val destFile = File(booksDir, "${System.currentTimeMillis()}.txt")
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("无法读取文件")

        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        val text = readTextAutoEncoding(destFile)
        val chunks = buildChunks(text)
        return Triple(fileName, destFile.absolutePath, chunks.size)
    }

    // 读取指定块的内容
    fun readChunk(filePath: String, chunkIndex: Int): String {
        val chunks = buildChunks(readTextAutoEncoding(File(filePath)))
        return chunks.getOrNull(chunkIndex)?.content ?: ""
    }

    fun getAllChunks(filePath: String): List<Chunk> {
        val text = readTextAutoEncoding(File(filePath))
        return buildChunks(text)
    }
    private fun readTextAutoEncoding(file: File): String {
        // 依次尝试常见编码
        val encodings = listOf("UTF-8", "GBK", "GB2312", "GB18030", "BIG5", "UTF-16")
        for (encoding in encodings) {
            try {
                val text = file.readText(charset(encoding))
                // 简单验证：如果包含大量乱码字符就跳过
                val garbledCount = text.count { it == '?' || it.code == 0xFFFD }
                if (garbledCount < text.length * 0.01) {
                    return text
                }
            } catch (e: Exception) {
                continue
            }
        }
        return file.readText(Charsets.UTF_8) // 兜底
    }
    private fun buildChunks(fullText: String): List<Chunk> {
        // 先尝试章节识别
        val chapters = detectChapters(fullText)
        return if (chapters.size >= 2) {
            buildFromChapters(chapters, fullText)
        } else {
            buildByCharCount(fullText)
        }
    }

    private fun detectChapters(text: String): List<Pair<Int, String>> {
        val result = mutableListOf<Pair<Int, String>>()
        var pos = 0
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && chapterPatterns.any { it.matches(trimmed) }) {
                result.add(Pair(pos, trimmed))
            }
            pos += line.length + 1
        }
        return result
    }

    private fun buildFromChapters(
        starts: List<Pair<Int, String>>,
        fullText: String
    ): List<Chunk> {
        val result = mutableListOf<Chunk>()
        var globalIndex = 0
        starts.forEachIndexed { i, (pos, title) ->
            val endPos = if (i + 1 < starts.size) starts[i + 1].first else fullText.length
            val content = fullText.substring(pos, endPos).trim()
            if (content.length <= 6000) {
                result.add(Chunk(globalIndex++, title, content, pos))
            } else {
                // 超长章节继续细分
                splitText(content).forEachIndexed { j, (sub, offset) ->
                    result.add(Chunk(globalIndex++, "${title}（${j + 1}）", sub, pos + offset))
                }
            }
        }
        return result
    }

    private fun buildByCharCount(fullText: String): List<Chunk> {
        return splitText(fullText).mapIndexed { i, (content, pos) ->
            Chunk(i, "第${i + 1}段", content, pos)
        }
    }

    private fun splitText(text: String): List<Pair<String, Int>> {
        val result = mutableListOf<Pair<String, Int>>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + CHUNK_SIZE, text.length)
            if (end < text.length) {
                val searchFrom = maxOf(end - 200, start)
                end = findBreakPoint(text, searchFrom, end)
            }
            if (end <= start) {
                end = minOf(start + CHUNK_SIZE, text.length)
            }

            result.add(Pair(text.substring(start, end), start))
            start = end
        }
        return result
    }

    private fun findBreakPoint(text: String, from: Int, to: Int): Int {
        val sub = text.substring(from, to)
        val lastNewline = sub.lastIndexOf('\n')
        if (lastNewline > 0) return from + lastNewline + 1
        for (i in sub.indices.reversed()) {
            if (sub[i] in listOf('。', '！', '？', '…', '"')) return from + i + 1
        }
        val lastComma = sub.lastIndexOf('，')
        if (lastComma > 0) return from + lastComma + 1
        return to
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "未知书名"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    name = (cursor.getString(idx) ?: "未知书名")
                        .removeSuffix(".txt")
                        .replace("/", "_")
                        .replace("\\", "_")
                        .ifBlank { "未知书名" }
                }
            }
        }
        return name
    }
}