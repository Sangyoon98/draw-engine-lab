package com.example.drawenginelab.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.drawenginelab.core.CanvasTransform
import com.example.drawenginelab.core.PenOptionsRow
import com.example.drawenginelab.core.RichTextEditController
import com.example.drawenginelab.core.TextFormatBar
import com.example.drawenginelab.core.TextLayer
import com.example.drawenginelab.core.TextLayerState
import com.example.drawenginelab.core.PALETTE
import com.example.drawenginelab.core.TitleBar
import com.example.drawenginelab.core.ToolChip
import com.example.drawenginelab.core.ZoomPanBox
import com.example.drawenginelab.core.handleAir
import com.example.drawenginelab.core.rememberSPen
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

enum class PenKind(val label: String) {
    FOUNTAIN("만년필"),
    BALLPOINT("볼펜"),
    MARKER("마커"),
    HIGHLIGHTER("형광펜"),
    PENCIL("연필"),
    BRUSH("브러시"),
    ERASER_OBJECT("획지우개"),
    ERASER_PIXEL("픽셀지우개"),
}

internal class SPoint(val x: Float, val y: Float, val pressure: Float)

internal class NStroke(val kind: PenKind, val color: Color, val base: Float) {
    val pts = ArrayList<SPoint>()
    private var minX = Float.POSITIVE_INFINITY
    private var minY = Float.POSITIVE_INFINITY
    private var maxX = Float.NEGATIVE_INFINITY
    private var maxY = Float.NEGATIVE_INFINITY

    private var cacheN = -1
    private var cacheSmooth: List<SPoint> = emptyList()
    private var cacheWidth: FloatArray = FloatArray(0)

    fun add(o: Offset, p: Float) {
        pts.add(SPoint(o.x, o.y, p))
        minX = min(minX, o.x); minY = min(minY, o.y)
        maxX = max(maxX, o.x); maxY = max(maxY, o.y)
    }

    fun hit(x: Float, y: Float, pad: Float): Boolean =
        x in (minX - pad)..(maxX + pad) && y in (minY - pad)..(maxY + pad)

    fun render(): Pair<List<SPoint>, FloatArray> {
        if (cacheN != pts.size) {
            cacheSmooth = smooth(pts)
            cacheWidth = widths(kind, base, cacheSmooth)
            cacheN = pts.size
        }
        return cacheSmooth to cacheWidth
    }

    fun seed(): Int {
        val f = pts.firstOrNull() ?: return 0
        return (f.x.toInt() * 73856093) xor (f.y.toInt() * 19349663) xor pts.size
    }
}

class NativeDrawController {
    private val strokes = ArrayList<NStroke>()
    private val undone = ArrayList<NStroke>()
    private var current: NStroke? = null

    var revision by mutableStateOf(0)
        private set
    var hover: Offset? by mutableStateOf(null)
        private set
    // bumps only when the COMMITTED set changes (commit/undo/redo/erase/clear) — drives
    // the baked-bitmap cache rebuild, separate from per-point [revision].
    var sceneVersion by mutableStateOf(0)
        private set

    private fun bump() { revision++ }

    internal fun strokeList(): List<NStroke> = strokes
    internal fun currentStroke(): NStroke? = current

    internal fun start(kind: PenKind, color: Color, size: Float, o: Offset, p: Float) {
        current = NStroke(kind, color, size).also { it.add(o, p) }; bump()
    }
    internal fun extend(o: Offset, p: Float) { current?.add(o, p); bump() }
    internal fun commit() {
        var added = false
        current?.let { if (it.pts.size > 1) { strokes.add(it); undone.clear(); added = true } }
        current = null; bump(); if (added) sceneVersion++
    }
    internal fun cancelCurrent() { if (current != null) { current = null; bump() } }
    internal fun eraseAt(x: Float, y: Float) {
        if (strokes.removeAll { it.hit(x, y, ERASE_PAD) }) { undone.clear(); sceneVersion++ }
        bump()
    }
    internal fun setHover(o: Offset?) { hover = o }

    fun undo() { if (strokes.isNotEmpty()) { undone.add(strokes.removeAt(strokes.lastIndex)); bump(); sceneVersion++ } }
    fun redo() { if (undone.isNotEmpty()) { strokes.add(undone.removeAt(undone.lastIndex)); bump(); sceneVersion++ } }
    fun clear() { if (strokes.isNotEmpty() || current != null) { strokes.clear(); undone.clear(); current = null; bump(); sceneVersion++ } }

    companion object { const val ERASE_PAD = 24f }
}

@Composable
fun NativeCanvas(
    controller: NativeDrawController,
    pen: PenKind,
    color: Color,
    size: Float,
    modifier: Modifier = Modifier,
) {
    val cache = remember { SceneCache() }
    Canvas(
        modifier
            .pointerInput(pen, color, size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    controller.setHover(null)
                    val objErase = pen == PenKind.ERASER_OBJECT || down.type == PointerType.Eraser
                    down.consume()
                    if (objErase) {
                        controller.eraseAt(down.position.x, down.position.y)
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) break
                            e.changes.firstOrNull { it.id == down.id }?.let {
                                controller.eraseAt(it.position.x, it.position.y); it.consume()
                            }
                            if (e.changes.none { it.pressed }) break
                        }
                    } else {
                        controller.start(pen, color, size, down.position, down.pressure)
                        while (true) {
                            val e = awaitPointerEvent()
                            if (e.changes.count { it.pressed } >= 2) { controller.cancelCurrent(); break }
                            e.changes.firstOrNull { it.id == down.id }?.let { ch ->
                                ch.historical.forEach { controller.extend(it.position, ch.pressure) }
                                controller.extend(ch.position, ch.pressure)
                                ch.consume()
                            }
                            if (e.changes.none { it.pressed }) break
                        }
                        controller.commit()
                    }
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull()
                        when {
                            ch == null || ch.pressed -> controller.setHover(null)
                            ch.type == PointerType.Stylus -> controller.setHover(ch.position)
                            else -> controller.setHover(null)
                        }
                    }
                }
            },
    ) {
        controller.revision
        val sv = controller.sceneVersion
        val w = drawContext.size.width.toInt().coerceAtLeast(1)
        val h = drawContext.size.height.toInt().coerceAtLeast(1)
        // Bake committed strokes into a bitmap once (rebuilt only when the scene changes),
        // then blit it + draw only the live stroke → render cost is O(1) per frame no matter
        // how many strokes exist (Samsung Notes / smartlearning trick). Tradeoff: raster, so
        // zooming in pixelates the baked content.
        if (cache.bmp == null || cache.w != w || cache.h != h || cache.version != sv) {
            val bmp = ImageBitmap(w, h)
            CanvasDrawScope().draw(this, layoutDirection, GraphicsCanvas(bmp), Size(w.toFloat(), h.toFloat())) {
                controller.strokeList().forEach { renderStroke(it) }
            }
            cache.bmp = bmp; cache.w = w; cache.h = h; cache.version = sv
        }
        drawImage(cache.bmp!!)
        controller.currentStroke()?.let { if (it.kind != PenKind.ERASER_PIXEL) renderStroke(it) }
        controller.hover?.let { drawCircle(Color(0x551565C0), radius = size, center = it) }
    }
}

private class SceneCache {
    var bmp: ImageBitmap? = null
    var w = 0
    var h = 0
    var version = -1
}

// --- clean-room smoothing + per-kind dynamics --------------------------------

private fun smooth(pts: List<SPoint>): List<SPoint> {
    if (pts.size < 3) return pts
    val out = ArrayList<SPoint>(pts.size * 4)
    for (i in 0 until pts.size - 1) {
        val p0 = pts[max(0, i - 1)]; val p1 = pts[i]; val p2 = pts[i + 1]; val p3 = pts[min(pts.size - 1, i + 2)]
        val steps = (hypot(p2.x - p1.x, p2.y - p1.y) / 5f).toInt().coerceIn(1, 8)
        for (s in 0 until steps) {
            val t = s.toFloat() / steps
            out.add(
                SPoint(
                    cr(p0.x, p1.x, p2.x, p3.x, t),
                    cr(p0.y, p1.y, p2.y, p3.y, t),
                    p1.pressure + (p2.pressure - p1.pressure) * t,
                ),
            )
        }
    }
    out.add(pts.last())
    return out
}

private fun cr(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
    val t2 = t * t; val t3 = t2 * t
    return 0.5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
}

private class WCfg(val minMul: Float, val maxMul: Float, val speedThin: Boolean, val taperLen: Float)

private fun cfg(kind: PenKind): WCfg = when (kind) {
    PenKind.FOUNTAIN -> WCfg(0.20f, 1.25f, true, 18f)
    PenKind.BRUSH -> WCfg(0.12f, 1.90f, true, 28f)
    PenKind.BALLPOINT -> WCfg(0.80f, 1.05f, false, 4f)
    PenKind.PENCIL -> WCfg(0.45f, 1.00f, false, 6f)
    else -> WCfg(0.5f, 1f, false, 0f)
}

private fun widths(kind: PenKind, base: Float, sm: List<SPoint>): FloatArray {
    val n = sm.size
    val w = FloatArray(n)
    if (n == 0) return w
    val c = cfg(kind)
    for (i in 0 until n) {
        val pr = sm[i].pressure.coerceIn(0f, 1f)
        var mul = c.minMul + (c.maxMul - c.minMul) * pr
        if (c.speedThin && i > 0) {
            val sp = hypot(sm[i].x - sm[i - 1].x, sm[i].y - sm[i - 1].y)
            mul *= 1f - (sp / 45f).coerceIn(0f, 0.6f)
        }
        w[i] = base * mul
    }
    val sw = FloatArray(n)
    for (i in 0 until n) {
        var sum = 0f; var cnt = 0
        for (k in max(0, i - 2)..min(n - 1, i + 2)) { sum += w[k]; cnt++ }
        sw[i] = sum / cnt
    }
    if (c.taperLen > 0f && n > 1) {
        val fromStart = FloatArray(n); val fromEnd = FloatArray(n)
        for (i in 1 until n) fromStart[i] = fromStart[i - 1] + hypot(sm[i].x - sm[i - 1].x, sm[i].y - sm[i - 1].y)
        for (i in n - 2 downTo 0) fromEnd[i] = fromEnd[i + 1] + hypot(sm[i + 1].x - sm[i].x, sm[i + 1].y - sm[i].y)
        for (i in 0 until n) {
            val d = min(fromStart[i], fromEnd[i])
            sw[i] *= (d / c.taperLen).coerceIn(0.15f, 1f)
        }
    }
    return sw
}

private fun DrawScope.renderStroke(s: NStroke) {
    if (s.pts.isEmpty()) return
    // translucent pens (not the highlighter/pencil which already blend per-dab): draw opaque
    // into a layer, then composite once — overlapping segments would otherwise stack alpha
    val layered = s.color.alpha < 1f && s.kind != PenKind.HIGHLIGHTER && s.kind != PenKind.PENCIL
    if (layered) {
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint().apply { alpha = s.color.alpha })
        renderKind(s, s.color.copy(alpha = 1f))
        drawContext.canvas.restore()
    } else renderKind(s, s.color)
}

private fun DrawScope.renderKind(s: NStroke, color: Color) {
    val (sm, w) = s.render()
    when (s.kind) {
        PenKind.HIGHLIGHTER -> constPath(sm, s.color.copy(alpha = 0.30f * s.color.alpha), s.base * 3.5f, StrokeCap.Square)
        PenKind.MARKER -> constPath(sm, color, s.base * 2.3f, StrokeCap.Square)
        PenKind.PENCIL -> pencil(s, sm, w)
        PenKind.ERASER_PIXEL -> clearPath(sm, s.base * 2.5f)
        PenKind.ERASER_OBJECT -> Unit
        else -> variableWidth(color, sm, w)
    }
}

private fun DrawScope.constPath(sm: List<SPoint>, color: Color, width: Float, cap: StrokeCap) {
    if (sm.size < 2) return
    val path = Path().apply {
        moveTo(sm[0].x, sm[0].y)
        for (i in 1 until sm.size) lineTo(sm[i].x, sm[i].y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = cap, join = StrokeJoin.Round))
}

private fun DrawScope.variableWidth(color: Color, sm: List<SPoint>, w: FloatArray) {
    for (i in 1 until sm.size) {
        drawLine(
            color = color,
            start = Offset(sm[i - 1].x, sm[i - 1].y),
            end = Offset(sm[i].x, sm[i].y),
            strokeWidth = max(0.6f, (w[i - 1] + w[i]) / 2f),
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.clearPath(sm: List<SPoint>, width: Float) {
    if (sm.size < 2) return
    val path = Path().apply {
        moveTo(sm[0].x, sm[0].y)
        for (i in 1 until sm.size) lineTo(sm[i].x, sm[i].y)
    }
    drawPath(path, Color.Black, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Clear)
}

private fun DrawScope.pencil(s: NStroke, sm: List<SPoint>, w: FloatArray) {
    val rnd = Random(s.seed())
    val r = s.base * 0.16f
    for (i in 1 until sm.size) {
        val a = sm[i - 1]; val b = sm[i]
        val dist = hypot(b.x - a.x, b.y - a.y)
        val steps = max(1, (dist / 2f).toInt())
        for (t in 0..steps) {
            val f = t.toFloat() / steps
            val pr = (a.pressure + (b.pressure - a.pressure) * f).coerceIn(0.2f, 1f)
            val spread = (w[i] / s.base) * s.base * 0.5f
            val jx = (rnd.nextFloat() - 0.5f) * spread
            val jy = (rnd.nextFloat() - 0.5f) * spread
            drawCircle(
                color = s.color.copy(alpha = 0.20f * pr * s.color.alpha),
                radius = r,
                center = Offset(a.x + (b.x - a.x) * f + jx, a.y + (b.y - a.y) * f + jy),
            )
        }
    }
}

// --- toolbar + screen --------------------------------------------------------

@Composable
fun CanvasToolbar(
    pen: PenKind, color: Color, size: Float,
    onPen: (PenKind) -> Unit, onColor: (Color) -> Unit, onSize: (Float) -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit, onClear: () -> Unit, onResetView: () -> Unit, onBack: () -> Unit,
    textMode: Boolean = false, onText: () -> Unit = {}, extraRow: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PenKind.entries.forEach { k -> ToolChip(k.label, !textMode && pen == k) { onPen(k) } }
            ToolChip("텍스트", textMode, onText)
            Spacer(Modifier.width(8.dp))
            ToolChip("되돌리기", false, onUndo)
            ToolChip("다시", false, onRedo)
            ToolChip("전체삭제", false, onClear)
            Spacer(Modifier.width(8.dp))
            ToolChip("화면맞춤", false, onResetView)
            ToolChip("뒤로", false, onBack)
        }
        Spacer(Modifier.size(6.dp))
        PenOptionsRow(color, size, onColor, onSize)
        extraRow()
    }
}

private fun nextPen(p: PenKind): PenKind {
    val all = PenKind.entries
    return all[(p.ordinal + 1) % all.size]
}

@Composable
fun CanvasScreen(onBack: () -> Unit) {
    val controller = remember { NativeDrawController() }
    val transform = remember { CanvasTransform() }
    var pen by remember { mutableStateOf(PenKind.FOUNTAIN) }
    var color by remember { mutableStateOf(PALETTE[0]) }
    var size by remember { mutableStateOf(8f) }
    var textMode by remember { mutableStateOf(false) }
    val texts = remember { TextLayerState() }
    val editor = remember { RichTextEditController() }

    val airGate = remember { longArrayOf(0L) }
    val spenOn = rememberSPen(
        onButton = { pen = nextPen(pen) },
        onAir = { dx, _ -> handleAir(airGate, dx, controller::undo, controller::redo) },
    )

    Column(Modifier.fillMaxSize()) {
        TitleBar("Canvas 버전 (삼성노트식 · 6펜)" + if (spenOn) "  ·  S펜 연결됨" else "")
        CanvasToolbar(
            pen = pen, color = color, size = size,
            onPen = { pen = it; textMode = false }, onColor = { color = it }, onSize = { size = it },
            onUndo = controller::undo, onRedo = controller::redo, onClear = { controller.clear(); texts.clear() },
            onResetView = transform::reset, onBack = onBack,
            textMode = textMode, onText = { textMode = true },
            extraRow = {
                if (textMode) {
                    TextFormatBar(editor, color, onDone = { texts.commit(editor) }, onDelete = texts::deleteEditing)
                }
            },
        )
        ZoomPanBox(transform, Modifier.fillMaxWidth().weight(1f)) {
            NativeCanvas(
                controller = controller,
                pen = pen, color = color, size = size,
                modifier = Modifier.fillMaxSize().background(Color.White),
            )
            // Android text stack (Spannable + StaticLayout) as its own unbaked layer above the
            // stroke bitmap: text stays vector-sharp under zoom even though strokes are raster.
            TextLayer(texts, textMode, editor, Modifier.fillMaxSize())
        }
    }
}
