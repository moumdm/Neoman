package com.local.comicreader.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 画面处理：白边裁剪 / 旋转 / 宽页拆分。
 * 全部基于位图局部操作，无额外内存翻倍（createBitmap 复用原像素缓冲时零拷贝）。
 */
object ImageOps {

    /** 宽高比超过该值判定为跨页大图，开启拆页时切为两半 */
    const val SPLIT_RATIO = 1.35f

    /**
     * 白边裁剪：抽样扫描边缘颜色，找出内容区域。
     * 节省不足 3% 时直接返回原图，避免无意义拷贝。
     */
    fun trimWhiteEdges(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val step = max(1, min(w, h) / 240)

        // 边缘基色取四角
        val corners = intArrayOf(
            safePixel(src, 0, 0), safePixel(src, w - 1, 0),
            safePixel(src, 0, h - 1), safePixel(src, w - 1, h - 1)
        )
        var br = 0; var bg = 0; var bb = 0
        corners.forEach { c -> br += (c shr 16) and 0xFF; bg += (c shr 8) and 0xFF; bb += c and 0xFF }
        br /= corners.size; bg /= corners.size; bb /= corners.size

        fun isContent(x: Int, y: Int): Boolean {
            val c = safePixel(src, x, y)
            val d = abs(((c shr 16) and 0xFF) - br) + abs(((c shr 8) and 0xFF) - bg) + abs((c and 0xFF) - bb)
            return d > 36
        }

        var top = -1; var bottom = -1; var left = -1; var right = -1
        outer@ for (y in 0 until h step step) {
            for (x in 0 until w step step) if (isContent(x, y)) { top = y; break@outer }
        }
        if (top < 0) return src
        outer@ for (y in (h - 1) downTo 0 step step) {
            for (x in 0 until w step step) if (isContent(x, y)) { bottom = min(h - 1, y + step); break@outer }
        }
        outer@ for (x in 0 until w step step) {
            for (y in top..bottom step step) if (isContent(x, y)) { left = x; break@outer }
        }
        outer@ for (x in (w - 1) downTo 0 step step) {
            for (y in top..bottom step step) if (isContent(x, y)) { right = min(w - 1, x + step); break@outer }
        }
        if (left < 0 || right <= left || bottom <= top) return src

        // 节省太小则不裁剪
        val saved = 1f - (right - left + 1f) * (bottom - top + 1f) / (w.toFloat() * h)
        if (saved < 0.03f) return src
        return Bitmap.createBitmap(src, left, top, right - left + 1, bottom - top + 1)
    }

    private fun safePixel(b: Bitmap, x: Int, y: Int): Int =
        try { b.getPixel(x, y) } catch (_: Exception) { 0 }

    /** 旋转 90/180/270 度 */
    fun rotate(src: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * 拆页：取左/右半幅。
     * @param second true=后半页；rtl 模式下阅读顺序为右先左后
     */
    fun splitHalf(src: Bitmap, second: Boolean, rtl: Boolean): Bitmap {
        val half = src.width / 2
        val takeRight = if (rtl) !second else second
        val x = if (takeRight) src.width - half else 0
        return Bitmap.createBitmap(src, x, 0, half, src.height)
    }

    /** 判断尺寸是否需要拆页 */
    fun needsSplit(w: Int, h: Int): Boolean = w.toFloat() / h.toFloat() > SPLIT_RATIO
}
