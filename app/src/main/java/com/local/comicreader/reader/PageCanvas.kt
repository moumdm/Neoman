package com.local.comicreader.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.local.comicreader.data.ReadBackground
import kotlin.math.abs

/** 单页缩放/平移状态（0.5 起无上限自由缩放） */
class ZoomPanState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
        private set

    val zoomed: Boolean get() = scale > 1.02f

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    fun applyZoom(centroid: Offset, factor: Float, canvas: Size) {
        val old = scale
        // 上限放到 100 倍：等效不限制，仅防浮点溢出
        val new = (old * factor).coerceIn(0.5f, 100f)
        if (abs(new - old) < 0.0001f) return
        val center = Offset(canvas.width / 2f, canvas.height / 2f)
        // 围绕手指中心缩放
        offset = (centroid - center) * (1f - new / old) + offset
        scale = new
        if (scale <= 1.02f) reset()
    }

    fun applyPan(delta: Offset, canvas: Size, fit: Size) {
        offset += delta
        clamp(canvas, fit)
    }

    /** 约束平移范围：缩放后不露出画布背景 */
    fun clamp(canvas: Size, fit: Size) {
        val cx = canvas.width / 2f
        val cy = canvas.height / 2f
        val bx = (canvas.width - fit.width) / 2f
        val by = (canvas.height - fit.height) / 2f
        var tlx = cx + (bx - cx) * scale + offset.x
        var tly = cy + (by - cy) * scale + offset.y
        val cw = fit.width * scale
        val ch = fit.height * scale
        tlx = if (cw >= canvas.width) tlx.coerceIn(canvas.width - cw, 0f) else (canvas.width - cw) / 2f
        tly = if (ch >= canvas.height) tly.coerceIn(canvas.height - ch, 0f) else (canvas.height - ch) / 2f
        offset = Offset(tlx - cx - (bx - cx) * scale, tly - cy - (by - cy) * scale)
    }

    /** 双击：≥ 2 倍 → 缩回原本大小；< 2 倍 → 精确放大到 2 倍 */
    fun toggleZoom(at: Offset, canvas: Size) {
        if (scale >= 2f) reset()
        else applyZoom(at, 2f / scale, canvas)
    }
}

/**
 * 页面画布：双指缩放 + 单指拖动（放大时）+ 单击/双击。
 * 未放大时不消费拖动事件，交给上层 Pager 翻页。
 */
@Composable
fun PageCanvas(
    bitmap: android.graphics.Bitmap?,
    background: ReadBackground,
    modifier: Modifier = Modifier,
    onTap: (Offset) -> Unit = {},
    onZoomChanged: ((Boolean) -> Unit)? = null
) {
    val state = remember { ZoomPanState() }
    val bgColor = background.toColor()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(bgColor)
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var handling = state.zoomed
                    while (true) {
                        val event: PointerEvent = awaitPointerEvent()
                        if (!event.changes.any { it.pressed }) break
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange != 1f) {
                            state.applyZoom(event.calculateCentroid(), zoomChange, Size(size.width.toFloat(), size.height.toFloat()))
                            handling = state.zoomed
                        }
                        if (handling && panChange != Offset.Zero) {
                            val fit = fitSizeOf(bitmap, Size(size.width.toFloat(), size.height.toFloat()))
                            state.applyPan(panChange, Size(size.width.toFloat(), size.height.toFloat()), fit)
                        }
                        if (handling) event.changes.forEach { it.consume() }
                        if (!state.zoomed) handling = false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // 传出归一化坐标（0~1），便于上层按屏幕分区处理
                    onTap = { pos ->
                        onTap(Offset(pos.x / size.width, pos.y / size.height))
                    },
                    onDoubleTap = { pos ->
                        state.toggleZoom(pos, Size(size.width.toFloat(), size.height.toFloat()))
                        onZoomChanged?.invoke(state.zoomed)
                    }
                )
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = state.scale
                        scaleY = state.scale
                        translationX = state.offset.x
                        translationY = state.offset.y
                    }
            )
        } else {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(36.dp),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** ContentScale.Fit 下的显示尺寸 */
private fun fitSizeOf(bitmap: android.graphics.Bitmap?, canvas: Size): Size {
    if (bitmap == null || canvas.width <= 0f || canvas.height <= 0f) return Size(1f, 1f)
    if (canvas.width / canvas.height >= bitmap.width / bitmap.height.toFloat()) {
        val w = canvas.height * bitmap.width / bitmap.height.toFloat()
        return Size(w, canvas.height)
    }
    val h = canvas.width * bitmap.height / bitmap.width.toFloat()
    return Size(canvas.width, h)
}

fun ReadBackground.toColor(): Color = when (this) {
    ReadBackground.BLACK -> Color.Black
    ReadBackground.GRAY -> Color(0xFF202020)
    ReadBackground.WHITE -> Color.White
    ReadBackground.SEPIA -> Color(0xFFF5ECD7)
}
