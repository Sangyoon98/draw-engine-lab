package com.example.drawenginelab.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.example.drawenginelab.notefile.NoteDoc
import com.example.drawenginelab.notefile.NotePen
import com.example.drawenginelab.notefile.NotePoint
import com.example.drawenginelab.notefile.NoteStroke

// Canvas strokes ↔ neutral note. The Canvas version saves in smartlearning's own format
// (SmartLearningJson), so its stroke = raw points + drawn width + color, like smartlearning.

// drawn width = base × this factor (see renderKind / widths)
private fun widthFactor(k: PenKind): Float = when (k) {
    PenKind.MARKER -> 2.3f
    PenKind.HIGHLIGHTER -> 3.5f
    PenKind.ERASER_PIXEL -> 2.5f
    PenKind.BALLPOINT -> 1.05f // full-pressure ballpoint, so a constant-width pen round-trips
    else -> 1f
}

private const val HIGHLIGHT_ALPHA = 0.30f

internal fun NativeDrawController.toNoteDoc(width: Float, height: Float): NoteDoc =
    NoteDoc(width, height, strokeList().filter { it.kind != PenKind.ERASER_OBJECT }.map { s ->
        val pen = when (s.kind) {
            PenKind.FOUNTAIN -> NotePen.PRESSURE
            PenKind.MARKER -> NotePen.MARKER
            PenKind.HIGHLIGHTER -> NotePen.HIGHLIGHTER
            PenKind.PENCIL -> NotePen.PENCIL
            PenKind.BRUSH -> NotePen.BRUSH
            PenKind.ERASER_PIXEL -> NotePen.ERASER
            else -> NotePen.PEN
        }
        // highlighter translucency is applied at draw time here, but formats without pen kinds
        // (smartlearning) only keep color, so bake it into the saved alpha
        val color = if (s.kind == PenKind.HIGHLIGHTER) s.color.copy(alpha = s.color.alpha * HIGHLIGHT_ALPHA) else s.color
        NoteStroke(pen, color.toArgb(), s.base * widthFactor(s.kind), s.pts.map { NotePoint(it.x, it.y, it.pressure) })
    })

internal fun NativeDrawController.load(doc: NoteDoc, scale: Float) {
    replaceAll(doc.strokes.filter { it.points.isNotEmpty() }.map { n ->
        val kind = when (n.pen) {
            NotePen.ERASER -> PenKind.ERASER_PIXEL
            NotePen.HIGHLIGHTER -> PenKind.HIGHLIGHTER
            NotePen.MARKER -> PenKind.MARKER
            NotePen.PENCIL -> PenKind.PENCIL
            NotePen.BRUSH -> PenKind.BRUSH
            NotePen.PRESSURE -> PenKind.FOUNTAIN
            else -> PenKind.BALLPOINT
        }
        var color = Color(n.argb)
        if (kind == PenKind.HIGHLIGHTER) color = color.copy(alpha = (color.alpha / HIGHLIGHT_ALPHA).coerceAtMost(1f))
        NStroke(kind, color, n.width * scale / widthFactor(kind)).also { s ->
            n.points.forEach { p -> s.add(Offset(p.x * scale, p.y * scale), if (p.hasPressure) p.pressure else 1f) }
        }
    })
}
