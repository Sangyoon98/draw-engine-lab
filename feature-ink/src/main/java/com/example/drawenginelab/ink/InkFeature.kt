package com.example.drawenginelab.ink

import android.graphics.Matrix
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke as DrawStroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.compose.createWithComposeColor
import androidx.ink.geometry.compose.toRect
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.compose.ui.platform.LocalContext
import com.example.drawenginelab.core.ColorDot
import com.example.drawenginelab.core.PALETTE
import com.example.drawenginelab.core.SIZES
import com.example.drawenginelab.core.SizeDot
import com.example.drawenginelab.core.TitleBar
import com.example.drawenginelab.core.ToolChip
import com.example.drawenginelab.core.ZoomPanBox
import com.example.drawenginelab.core.CanvasTransform
import com.example.drawenginelab.core.handleAir
import com.example.drawenginelab.core.rememberSPen

private const val ERASE_PAD = 30f

enum class InkPen(val label: String) {
    PEN("펜"),
    MARKER("마커"),
    HIGHLIGHTER("형광펜"),
    DASHED("점선"),
    ERASER_OBJECT("획지우개"),
    ERASER_PIXEL("픽셀지우개"),
}

fun brushForInk(pen: InkPen, color: Color, size: Float): Brush = when (pen) {
    InkPen.MARKER -> Brush.createWithComposeColor(StockBrushes.marker(), color, size * 1.8f, 0.1f)
    InkPen.HIGHLIGHTER -> Brush.createWithComposeColor(StockBrushes.highlighter(), color.copy(alpha = 0.3f), size * 3.5f, 0.1f)
    InkPen.DASHED -> Brush.createWithComposeColor(StockBrushes.dashedLine(), color, size, 0.1f)
    else -> Brush.createWithComposeColor(StockBrushes.pressurePen(), color, size, 0.1f)
}

internal sealed interface InkOp
internal class StrokeOp(val stroke: Stroke) : InkOp
internal class ClearOp(val pts: List<Offset>, val width: Float) : InkOp

class InkState {
    private val ops = ArrayList<InkOp>()
    private val undone = ArrayList<InkOp>()
    private val previewClear = ArrayList<Offset>()

    var revision by mutableStateOf(0)
        private set

    private fun bump() { revision++ }

    internal fun opList(): List<InkOp> = ops
    internal fun previewClearPts(): List<Offset> = previewClear

    internal fun addStroke(s: Stroke) { ops.add(StrokeOp(s)); undone.clear(); bump() }
    internal fun eraseObjectAt(p: Offset) {
        val hit = ops.filter {
            it is StrokeOp && it.stroke.shape.computeBoundingBox()?.toRect()?.inflate(30f)?.contains(p) == true
        }
        if (hit.isNotEmpty()) { ops.removeAll(hit); undone.clear() }
        bump()
    }
    internal fun startClear(o: Offset) { previewClear.clear(); previewClear.add(o); bump() }
    internal fun extendClear(o: Offset) { previewClear.add(o); bump() }
    internal fun commitClear(width: Float) {
        if (previewClear.size > 1) { ops.add(ClearOp(previewClear.toList(), width)); undone.clear() }
        previewClear.clear(); bump()
    }

    fun undo() { if (ops.isNotEmpty()) { undone.add(ops.removeAt(ops.lastIndex)); bump() } }
    fun redo() { if (undone.isNotEmpty()) { ops.add(undone.removeAt(undone.lastIndex)); bump() } }
    fun clear() { ops.clear(); undone.clear(); previewClear.clear(); bump() }
}

@Composable
fun InkCanvas(
    state: InkState,
    pen: InkPen,
    color: Color,
    size: Float,
    brush: Brush,
    renderer: CanvasStrokeRenderer,
    modifier: Modifier = Modifier,
) {
    val identity = remember { Matrix() }
    val pixelW = size * 5f

    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            state.revision
            val canvas = drawContext.canvas
            canvas.saveLayer(
                androidx.compose.ui.geometry.Rect(0f, 0f, drawContext.size.width, drawContext.size.height),
                androidx.compose.ui.graphics.Paint(),
            )
            val nc = canvas.nativeCanvas
            state.opList().forEach { op ->
                when (op) {
                    is StrokeOp -> renderer.draw(canvas = nc, stroke = op.stroke, strokeToScreenTransform = identity)
                    is ClearOp -> drawClear(op.pts, op.width)
                }
            }
            val pc = state.previewClearPts()
            if (pc.size > 1) drawClear(pc, pixelW)
            canvas.restore()
        }

        when (pen) {
            InkPen.ERASER_OBJECT -> Box(
                Modifier.fillMaxSize().pointerInput(pen) {
                    detectDragGestures(
                        onDragStart = { off -> state.eraseObjectAt(off) },
                        onDrag = { ch, _ -> state.eraseObjectAt(ch.position); ch.consume() },
                    )
                },
            )
            InkPen.ERASER_PIXEL -> Box(
                Modifier.fillMaxSize().pointerInput(pen, size) {
                    detectDragGestures(
                        onDragStart = { off -> state.startClear(off) },
                        onDrag = { ch, _ -> state.extendClear(ch.position); ch.consume() },
                        onDragEnd = { state.commitClear(pixelW) },
                        onDragCancel = { state.commitClear(pixelW) },
                    )
                },
            )
            // Ink's low-latency wet layer is a front buffer; whether it actually
            // displays varies by GPU/driver even when the EGL extensions are
            // advertised (e.g. SM-P610 advertises them but shows nothing until the
            // stroke commits). No reliable runtime probe exists, so we always render
            // the wet stroke ourselves with the same renderer + brush — visible on
            // every device and identical to the dry stroke, at ~1 frame of latency.
            else -> FallbackWetLayer(brush, renderer, identity) { state.addStroke(it) }
        }
    }
}

/**
 * Wet-ink fallback for devices without front-buffer support. Builds an
 * [InProgressStroke] from pointer input and draws it each frame with the *same*
 * [CanvasStrokeRenderer] and brush used for committed strokes, so the in-progress
 * line is visible on every device and looks identical before/after release.
 * Timestamps are a monotonic counter (stock brushes don't animate), pressure is
 * clamped away from 0, and only the tracked pointer is consumed so the parent
 * zoom/pan still sees a second finger.
 */
@Composable
private fun FallbackWetLayer(
    brush: Brush,
    renderer: CanvasStrokeRenderer,
    transform: Matrix,
    onFinish: (Stroke) -> Unit,
) {
    val inProgress = remember { InProgressStroke() }
    var version by remember { mutableStateOf(0) }
    var active by remember { mutableStateOf(false) }
    // Ink requires one constant stroke_unit_length (physical cm per coordinate unit)
    // across a whole stroke; leaving it unset makes Ink derive a per-input value that
    // varies and throws. Mirror the official view: one density-derived value for all.
    val ctx = LocalContext.current
    val cmPerUnit = remember(ctx) { 2.54f / ctx.resources.displayMetrics.densityDpi.coerceAtLeast(1) }
    val startMs = remember { longArrayOf(0L) }
    val lastMs = remember { longArrayOf(-1L) }

    // Ink's stroke model evolves over real time (it smooths/settles toward the input).
    // The official view steps it every frame; if we only step on input events the wet
    // line lags and snaps into place late ("worm" effect). So advance updateShape each
    // frame while drawing, using real elapsed ms (same clock as the input timestamps).
    LaunchedEffect(active) {
        while (active) {
            withFrameMillis { }
            inProgress.updateShape(SystemClock.uptimeMillis() - startMs[0])
            version++
        }
    }

    Box(
        Modifier.fillMaxSize().pointerInput(brush) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                inProgress.start(brush)
                startMs[0] = SystemClock.uptimeMillis()
                lastMs[0] = -1L
                fun push(x: Float, y: Float, pressure: Float) {
                    var e = SystemClock.uptimeMillis() - startMs[0]
                    if (e <= lastMs[0]) e = lastMs[0] + 1 // Ink needs strictly increasing times
                    lastMs[0] = e
                    val batch = MutableStrokeInputBatch()
                    batch.add(
                        StrokeInput.create(
                            x, y, e, InputToolType.TOUCH,
                            cmPerUnit, // strokeUnitLengthCm — must be constant across the stroke
                            pressure.coerceIn(0.05f, 1f),
                            StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION,
                        ),
                    )
                    inProgress.enqueueInputs(batch, MutableStrokeInputBatch())
                    inProgress.updateShape(e)
                }
                push(down.position.x, down.position.y, down.pressure)
                active = true; version++
                down.consume()
                while (true) {
                    val event = awaitPointerEvent()
                    val ch = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    push(ch.position.x, ch.position.y, ch.pressure)
                    version++
                    ch.consume()
                }
                inProgress.finishInput()
                // let the model fully settle so the committed stroke matches the wet one
                var guard = 0
                do {
                    inProgress.updateShape(SystemClock.uptimeMillis() - startMs[0] + guard * 16L)
                    guard++
                } while (inProgress.isUpdateNeeded() && guard < 120)
                onFinish(inProgress.toImmutable())
                active = false; version++
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            version // redraw as the in-progress stroke grows / settles
            if (active) {
                try {
                    renderer.draw(drawContext.canvas.nativeCanvas, inProgress, transform)
                } catch (e: Throwable) {
                    android.util.Log.e("InkWet", "fallback draw failed", e)
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawClear(pts: List<Offset>, width: Float) {
    if (pts.size < 2) return
    val path = Path().apply {
        moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(path, Color.Black, style = DrawStroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
}

@Composable
fun InkToolbar(
    pen: InkPen, color: Color, size: Float,
    onPen: (InkPen) -> Unit, onColor: (Color) -> Unit, onSize: (Float) -> Unit,
    onUndo: () -> Unit, onRedo: () -> Unit, onClear: () -> Unit, onResetView: () -> Unit, onBack: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            InkPen.entries.forEach { p -> ToolChip(p.label, pen == p) { onPen(p) } }
            Spacer(Modifier.width(8.dp))
            ToolChip("되돌리기", false, onUndo)
            ToolChip("다시", false, onRedo)
            ToolChip("전체삭제", false, onClear)
            Spacer(Modifier.width(8.dp))
            ToolChip("화면맞춤", false, onResetView)
            ToolChip("뒤로", false, onBack)
        }
        Spacer(Modifier.size(6.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PALETTE.forEach { c -> ColorDot(c, selected = c == color) { onColor(c) } }
            Spacer(Modifier.width(10.dp))
            SIZES.forEach { s -> SizeDot(s, selected = s == size, color = color) { onSize(s) } }
        }
    }
}

private fun nextInkPen(p: InkPen): InkPen {
    val all = InkPen.entries
    return all[(p.ordinal + 1) % all.size]
}

@Composable
fun InkScreen(onBack: () -> Unit) {
    val state = remember { InkState() }
    val transform = remember { CanvasTransform() }
    var pen by remember { mutableStateOf(InkPen.PEN) }
    var color by remember { mutableStateOf(PALETTE[0]) }
    var size by remember { mutableStateOf(SIZES[1]) }
    val renderer = remember { CanvasStrokeRenderer.create() }
    val brush = remember(pen, color, size) { brushForInk(pen, color, size) }

    val airGate = remember { longArrayOf(0L) }
    val spenOn = rememberSPen(
        onButton = { pen = nextInkPen(pen) },
        onAir = { dx, _ -> handleAir(airGate, dx, state::undo, state::redo) },
    )

    Column(Modifier.fillMaxSize()) {
        TitleBar("Ink API 버전" + if (spenOn) "  ·  S펜 연결됨" else "")
        InkToolbar(
            pen = pen, color = color, size = size,
            onPen = { pen = it }, onColor = { color = it }, onSize = { size = it },
            onUndo = state::undo, onRedo = state::redo, onClear = state::clear,
            onResetView = transform::reset, onBack = onBack,
        )
        ZoomPanBox(transform, Modifier.fillMaxWidth().weight(1f)) {
            InkCanvas(
                state = state,
                pen = pen, color = color, size = size,
                brush = brush,
                renderer = renderer,
                modifier = Modifier.fillMaxSize().background(Color.White),
            )
        }
    }
}
