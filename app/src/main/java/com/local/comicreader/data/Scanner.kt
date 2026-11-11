package com.local.comicreader.data

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次书库扫描的结果：books 为找到的书；failedRoots 为读取失败的根目录（授权失效等），其下的书不做失效清理，防止整架误删 */
data class ScanResult(val books: List<Book>, val failedRoots: Set<String>)

/**
 * 书库扫描：遍历 SAF 目录树，识别漫画文件与图片文件夹。
 * 深度上限 3 层，超大目录也不会卡死（IO 线程执行）。
 */
object BookScanner {

    private const val MAX_DEPTH = 3

    suspend fun scanAll(context: Context): ScanResult = withContext(Dispatchers.IO) {
        val seen = HashSet<String>()
        val out = mutableListOf<Book>()
        val failedRoots = mutableSetOf<String>()
        for (rootUri in Prefs.roots()) {
            val root = try {
                DocumentFile.fromTreeUri(context, Uri.parse(rootUri))
            } catch (_: Exception) { null }
            if (root == null) {
                failedRoots.add(rootUri)
                continue
            }
            out += scanDir(context, root, 0, seen, rootUri, failedRoots)
        }
        ScanResult(out.sortedByDescending { it.lastReadAt }, failedRoots)
    }

    private fun scanDir(
        context: Context,
        dir: DocumentFile,
        depth: Int,
        seen: HashSet<String>,
        rootUri: String,
        failedRoots: MutableSet<String>
    ): List<Book> {
        val out = mutableListOf<Book>()
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            // 目录不可读（如授权失效）：标记整个根，后续清理时跳过该根下的书
            failedRoots.add(rootUri)
            return out
        }
        for (child in children) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                if (containsImagesDirect(child)) {
                    makeBook(child, ComicFormat.IMAGES)?.let { if (seen.add(it.id)) out.add(it) }
                } else if (depth < MAX_DEPTH) {
                    out.addAll(scanDir(context, child, depth + 1, seen, rootUri, failedRoots))
                }
            } else if (child.isFile) {
                val fmt = ComicFormat.fromName(name)
                if (fmt != null) {
                    makeBook(child, fmt)?.let { if (seen.add(it.id)) out.add(it) }
                }
            }
        }
        return out
    }

    /** 目录内直接包含图片文件 → 视为一部图片文件夹漫画 */
    private fun containsImagesDirect(dir: DocumentFile): Boolean {
        val children = try { dir.listFiles() } catch (_: Exception) { return false }
        return children.any { c ->
            c.isFile && ComicFormat.fromName(c.name) == ComicFormat.IMAGES
        }
    }

    private fun makeBook(file: DocumentFile, fmt: ComicFormat): Book? {
        val uri = try { file.uri.toString() } catch (_: Exception) { return null }
        return Book(
            id = Book.idOf(uri),
            uri = uri,
            title = Book.titleFromName(file.name ?: "未命名"),
            format = if (fmt == ComicFormat.IMAGES) ComicFormat.IMAGES else fmt,
            size = try { file.length() } catch (_: Exception) { 0L },
            addedAt = System.currentTimeMillis()
        )
    }
}

/** 自然排序（数字部分按数值比较），用于页面顺序 */
fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ai = i; var bj = j
            while (ai < a.length && a[ai].isDigit()) ai++
            while (bj < b.length && b[bj].isDigit()) bj++
            val na = a.substring(i, ai).trimLeadingZeros()
            val nb = b.substring(j, bj).trimLeadingZeros()
            val cmp = na.length.compareTo(nb.length).let { if (it != 0) it else na.compareTo(nb) }
            if (cmp != 0) return cmp
            i = ai; j = bj
        } else {
            val cmp = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (cmp != 0) return cmp
            i++; j++
        }
    }
    return a.length - b.length
}

private fun String.trimLeadingZeros(): String {
    var s = 0
    while (s < length - 1 && this[s] == '0') s++
    return substring(s)
}
