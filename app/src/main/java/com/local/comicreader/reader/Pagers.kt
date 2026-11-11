package com.local.comicreader.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.local.comicreader.data.PageEffect
import com.local.comicreader.data.PageLayout
import com.local.comicreader.data.PageSpec
import com.local.comicreader.data.ReadDirection
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 阅读翻页引擎：
 * - RTL / LTR：HorizontalPager（RTL 反转布局），单页独立缩放
 * - VERTICAL：LazyColumn 连续滚动（条漫优化，无分页切割感），
 *   捏合时整条内容（所有页）等比放大，放大后横向拖动平移、纵向照常滚动
 * - 双页跨页：横向模式下一屏显示相邻两页
 * - 翻页效果：平移 / 仿真（透视旋转近似）/ 淡入 / 直接切换
 */
@Composable
fun ReaderPager(
    vm: ReaderViewModel,
    pagerState: PagerState,
    listState: LazyListState,
    onToggleMenu: () -> Unit,
    onTapFraction: (Offset) -> Unit
) {
    val specs = vm.pageSpecs
    val direction = vm.direction
    val layout = vm.layout
    val effect = vm.effect
    val bg = vm.settings.background

    if (direction == ReadDirection.VERTICAL) {
        // 条漫整体缩放：捏合时整条内容（所有页）按同一比例放大，
        // 放大后单指横向拖动平移整条、纵向照常连续滚动；双击在 1x/2x 间切换。
        // 与左右翻页模式的"单页独立缩放"相互独立。
        val zoom = remember { WebtoonZoomState() }
        var containerW by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()

        // 缩放后按比例补偿滚动位置，视线停留的内容不跳变
        fun zoomTo(target: Float, centroidX: Float) {
            if (containerW <= 0f) return
            val old = zoom.scale
            if (target == old) return
            val idx = listState.firstVisibleItemIndex
            val off = listState.firstVisibleItemScrollOffset
            zoom.setScale(target, centroidX, containerW)
            val k = zoom.scale / old
            if (k != 1f) scope.launch { listState.scrollToItem(idx, (off * k).toInt()) }
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(bg.toColor())
                .clipToBounds()
                .onSizeChanged { containerW = it.width.toFloat() }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var panH = false   // 本手势是否已锁定为横向平移
                        while (true) {
                            val event: PointerEvent = awaitPointerEvent()
                            if (!event.changes.any { it.pressed }) break
                            var handled = false
                            val zoomChange = event.calculateZoom()
                            if (zoomChange != 1f) {
                                zoomTo(zoom.scale * zoomChange, event.calculateCentroid().x)
                                handled = true
                                panH = false
                            }
                            if (!handled && zoom.zoomed) {
                                val pan = event.calculatePan()
                                // 横向为主的拖动 → 平移整条内容；纵向拖动不消费，交给列表滚动
                                if (pan != Offset.Zero && (panH || abs(pan.x) > abs(pan.y))) {
                                    panH = true
                                    zoom.panBy(pan.x, containerW)
                                    handled = true
                                }
                            }
                            if (handled) event.changes.forEach { it.consume() }
                        }
                    }
                }
        ) {
            // 列宽 = 容器宽 × 缩放比：所有页随之等比变宽变高；横向位置由 offsetX 平移
            val contentW = with(density) { (containerW * zoom.scale).toDp() }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .then(
                        if (contentW > 0.dp) Modifier.requiredWidth(contentW)
                        else Modifier.fillMaxWidth()
                    )
                    .graphicsLayer { translationX = zoom.offsetX }
                    .background(bg.toColor())
            ) {
                itemsIndexed(specs, key = { _, s -> s.hashCode() }) { _, spec ->
                    WebtoonPage(vm, spec, bg, onToggleMenu) { pageX ->
                        // 触点从页坐标换算到视口坐标（页起点带横向偏移）
                        scope.launch { zoomTo(if (zoom.zoomed) 1f else 2f, pageX + zoom.offsetX) }
                    }
                }
                item(key = "tail") {
                    Box(Modifier.fillMaxWidth().height(56.dp))
                }
            }
        }
    } else {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .background(bg.toColor()),
            reverseLayout = direction == ReadDirection.RTL
        ) { page ->
            val pageOffset = (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
            Box(
                Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer {
                        when (effect) {
                            PageEffect.SLIDE -> {}
                            PageEffect.FADE -> alpha = 1f - abs(pageOffset).coerceAtMost(1f)
                            PageEffect.SIMULATION -> {
                                cameraDistance = 14f * density
                                rotationY = -pageOffset * 32f
                                alpha = 1f - abs(pageOffset) * 0.35f
                            }
                            PageEffect.NONE -> alpha = if (abs(pageOffset) < 0.02f) 1f else 0f
                        }
                    }
            ) {
                if (layout == PageLayout.DUAL) {
                    val first = specs.getOrNull(page * 2)
                    val second = specs.getOrNull(page * 2 + 1)
                    Row(Modifier.fillMaxSize()) {
                        // RTL 时左半屏是第二页
                        val leftSpec = if (direction == ReadDirection.RTL) second else first
                        val rightSpec = if (direction == ReadDirection.RTL) first else second
                        if (leftSpec != null) PageSlot(vm, leftSpec, bg, onToggleMenu, Modifier.fillMaxHeight().weight(1f), onTapFraction)
                        if (rightSpec != null) PageSlot(vm, rightSpec, bg, onToggleMenu, Modifier.fillMaxHeight().weight(1f), onTapFraction)
                    }
                } else {
                    specs.getOrNull(page)?.let { PageSlot(vm, it, bg, onToggleMenu, Modifier.fillMaxSize(), onTapFraction) }
                }
            }
        }
    }
}

/** 横向模式单页槽位：加载位图 + 手势画布 + 翻页点击分区 */
@Composable
fun PageSlot(
    vm: ReaderViewModel,
    spec: PageSpec,
    bg: com.local.comicreader.data.ReadBackground,
    onToggleMenu: () -> Unit,
    modifier: Modifier = Modifier,
    onZoneTap: (Offset) -> Unit = { pos ->
        // 默认三分区点击：点击区域与方向有关，由 ReaderScreen 注入；这里兜底居中开菜单
        onToggleMenu()
    }
) {
    val bmp by produceState<android.graphics.Bitmap?>(null, spec, vm.settings.pipelineHash, vm) {
        value = vm.loadBitmap(spec)
    }
    PageCanvas(bmp, bg, modifier, onTap = onZoneTap)
}

/** 条漫页：占满（可能已整体放大的）列宽、按图片比例决定高度；
 *  单击开菜单，双击切换整条缩放（由上层 ReaderPager 统一处理比例与偏移） */
@Composable
private fun WebtoonPage(
    vm: ReaderViewModel,
    spec: PageSpec,
    bg: com.local.comicreader.data.ReadBackground,
    onToggleMenu: () -> Unit,
    onDoubleTap: (Float) -> Unit
) {
    val bmp by produceState<android.graphics.Bitmap?>(null, spec, vm.settings.pipelineHash, vm) {
        value = vm.loadBitmap(spec)
    }
    Box(
        Modifier
            .fillMaxWidth()
            .background(bg.toColor())
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleMenu() },
                    onDoubleTap = { pos -> onDoubleTap(pos.x) }
                )
            }
    ) {
        val bmpNow = bmp
        if (bmpNow != null) {
            val ratio = bmpNow.width.toFloat() / bmpNow.height
            Image(
                bitmap = bmpNow.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(ratio)
            )
        } else {
            Box(
                Modifier.fillMaxWidth().height(720.dp),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary) }
        }
    }
}

/** 条漫整体缩放状态：整条滚动内容共用同一比例与横向偏移 */
class WebtoonZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set

    val zoomed: Boolean get() = scale > 1.02f

    /** 缩放到目标比例，保持 centroidX 处的内容不漂移；回到 1x 时归位 */
    fun setScale(target: Float, centroidX: Float, containerW: Float) {
        val old = scale
        val new = target.coerceIn(1f, 5f)
        if (new == old) return
        if (new <= 1f) {
            scale = 1f
            offsetX = 0f
            return
        }
        var off = centroidX - (centroidX - offsetX) * (new / old)
        off = off.coerceIn(containerW - containerW * new, 0f)
        offsetX = off
        scale = new
    }

    /** 放大态下的横向平移（限制在内容宽度范围内） */
    fun panBy(dx: Float, containerW: Float) {
        if (!zoomed) return
        offsetX = (offsetX + dx).coerceIn(containerW - containerW * scale, 0f)
    }
}
