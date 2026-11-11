package com.local.comicreader.parser

import android.content.Context
import com.local.comicreader.data.Book
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

/**
 * EPUB 漫画解析：
 * container.xml → OPF 路径 → manifest + spine → 按 spine 顺序提取 XHTML 引用的图片。
 * 固定版式（FIXED-LAYOUT）图像 EPUB 可完美支持。
 */
class EpubBook(
    context: Context,
    book: Book
) : ZipBook(context, book.uri, buildSpine(context, book.uri)) {

    companion object {
        private fun streamOf(context: Context, uri: String, entry: String): ByteArray? {
            return try {
                val zis = java.util.zip.ZipInputStream(
                    java.io.BufferedInputStream(
                        context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!, 256 * 1024))
                zis.use {
                    var e = it.nextEntry
                    while (e != null) {
                        if (!e.isDirectory && e.name.replace('\\', '/') == entry) {
                            return it.readBytes()
                        }
                        e = it.nextEntry
                    }
                    null
                }
            } catch (_: Exception) { null }
        }

        /** 解析出按阅读顺序排列的 zip 条目名 */
        fun buildSpine(context: Context, uri: String): List<String> {
            return try {
                val container = streamOf(context, uri, "META-INF/container.xml") ?: return emptyList()
                val opfPath = parseContainer(container) ?: return emptyList()
                val opf = streamOf(context, uri, opfPath) ?: return emptyList()
                val base = opfPath.substringBeforeLast('/', "")
                val (manifest, spine) = parseOpf(opf)
                val ordered = mutableListOf<String>()
                for (idref in spine) {
                    val item = manifest[idref] ?: continue
                    if (item.type.startsWith("image/")) {
                        ordered.add(normalize(base, item.href))
                    } else if (item.type.contains("html") || item.type.contains("xml")) {
                        val xhtmlPath = normalize(base, item.href)
                        val xhtml = streamOf(context, uri, xhtmlPath) ?: continue
                        for (img in extractImageRefs(String(xhtml, StandardCharsets.UTF_8))) {
                            val p = normalize(xhtmlPath.substringBeforeLast('/', ""), img)
                            if (manifest.values.any { normalize(base, it.href) == p }) ordered.add(p)
                        }
                    }
                }
                if (ordered.isEmpty()) {
                    // 兜底：manifest 中全部图片，按 href 排序
                    manifest.values.map { normalize(base, it.href) }
                        .filter { it.substringAfterLast('.', "").lowercase() in imageExts }
                        .sorted()
                } else ordered
            } catch (_: Exception) { emptyList() }
        }

        private val imageExts = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif")

        private fun parseContainer(bytes: ByteArray): String? {
            val p = newParser(ByteArrayInputStream(bytes))
            var path: String? = null
            var inRootfile = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG ->
                        if (p.name.equals("rootfile", true)) {
                            inRootfile = true
                            for (i in 0 until p.attributeCount) {
                                if (p.getAttributeName(i).equals("full-path", true)) {
                                    path = p.getAttributeValue(i); break
                                }
                            }
                        }
                    XmlPullParser.END_TAG -> if (p.name.equals("rootfile", true)) inRootfile = false
                }
                if (path != null) break
            }
            return path
        }

        data class OpfItem(val href: String, val type: String)

        private fun parseOpf(bytes: ByteArray): Pair<Map<String, OpfItem>, List<String>> {
            val p = newParser(ByteArrayInputStream(bytes))
            val manifest = LinkedHashMap<String, OpfItem>()
            val spine = mutableListOf<String>()
            var section = ""
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> when {
                        p.name.equals("manifest", true) -> section = "m"
                        p.name.equals("spine", true) -> section = "s"
                        p.name.equals("item", true) && section == "m" -> {
                            var id = ""; var href = ""; var type = ""
                            for (i in 0 until p.attributeCount) {
                                when (p.getAttributeName(i).lowercase()) {
                                    "id" -> id = p.getAttributeValue(i)
                                    "href" -> href = p.getAttributeValue(i)
                                    "media-type" -> type = p.getAttributeValue(i)
                                }
                            }
                            if (id.isNotEmpty()) manifest[id] = OpfItem(href, type)
                        }
                        p.name.equals("itemref", true) && section == "s" -> {
                            for (i in 0 until p.attributeCount) {
                                if (p.getAttributeName(i).equals("idref", true)) {
                                    spine.add(p.getAttributeValue(i)); break
                                }
                            }
                        }
                    }
                }
            }
            return manifest to spine
        }

        /** 提取 XHTML 中的 <img src> / <image xlink:href> */
        private fun extractImageRefs(html: String): List<String> {
            val refs = mutableListOf<String>()
            val imgRe = Regex("""<img[^>]*\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            val imageRe = Regex("""<image[^>]*?(?:xlink:href|href)\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            for (m in imgRe.findAll(html)) refs.add(m.groupValues[1])
            for (m in imageRe.findAll(html)) refs.add(m.groupValues[1])
            // 去掉 #fragment 部分，保留路径
            return refs.map { it.substringBefore('#') }
        }

        private fun normalize(base: String, href: String): String {
            val decoded = java.net.URLDecoder.decode(href, StandardCharsets.UTF_8.name())
            val parts = (if (base.isEmpty()) decoded else "$base/$decoded").split('/')
            val stack = ArrayDeque<String>()
            for (part in parts) {
                when (part) {
                    "", "." -> {}
                    ".." -> if (stack.isNotEmpty()) stack.removeLast()
                    else -> stack.addLast(part)
                }
            }
            return stack.joinToString("/")
        }

        private fun newParser(input: ByteArrayInputStream): XmlPullParser {
            val f = XmlPullParserFactory.newInstance()
            f.isNamespaceAware = true
            val p = f.newPullParser()
            p.setInput(input, "utf-8")
            return p
        }
    }
}
