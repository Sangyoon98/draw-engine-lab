package com.example.drawenginelab.nativegl

import com.example.drawenginelab.notefile.NoteDoc
import com.example.drawenginelab.notefile.NotePen
import com.example.drawenginelab.notefile.NotePoint
import com.example.drawenginelab.notefile.NoteStroke

// Native stroke list (flat floats, see native-lib.cpp exportStrokes) ↔ neutral note.

// drawn width = base × this (native cfgFor().scale; ballpoint at full pressure)
private fun widthFactor(k: GlPen): Float = when (k) {
    GlPen.MARKER -> 2.3f
    GlPen.HIGHLIGHTER -> 3.5f
    GlPen.PENCIL -> 0.9f
    GlPen.ERASER_PIXEL -> 2.5f
    GlPen.BALLPOINT -> 1.05f
    else -> 1f
}

private const val HIGHLIGHT_ALPHA = 0.30f // native highlighter draws color.a × 0.3

internal fun strokesToNoteDoc(data: FloatArray, width: Float, height: Float): NoteDoc {
    val out = ArrayList<NoteStroke>()
    var i = 1
    repeat(data.getOrElse(0) { 0f }.toInt()) {
        val kind = GlPen.entries[data[i].toInt()]
        val r = data[i + 1]; val g = data[i + 2]; val b = data[i + 3]
        var a = data[i + 4]
        val base = data[i + 5]
        val n = data[i + 6].toInt()
        i += 7
        val pts = List(n) { k -> NotePoint(data[i + k * 3], data[i + k * 3 + 1], data[i + k * 3 + 2]) }
        i += n * 3
        if (kind == GlPen.ERASER_OBJECT) return@repeat
        if (kind == GlPen.HIGHLIGHTER) a *= HIGHLIGHT_ALPHA
        val pen = when (kind) {
            GlPen.FOUNTAIN -> NotePen.PRESSURE
            GlPen.MARKER -> NotePen.MARKER
            GlPen.HIGHLIGHTER -> NotePen.HIGHLIGHTER
            GlPen.PENCIL -> NotePen.PENCIL
            GlPen.BRUSH -> NotePen.BRUSH
            GlPen.ERASER_PIXEL -> NotePen.ERASER
            else -> NotePen.PEN
        }
        val argb = ((a * 255).toInt() shl 24) or ((r * 255).toInt() shl 16) or ((g * 255).toInt() shl 8) or (b * 255).toInt()
        out += NoteStroke(pen, argb, base * widthFactor(kind), pts)
    }
    return NoteDoc(width, height, out)
}

internal fun noteDocToStrokes(doc: NoteDoc, scale: Float): FloatArray {
    val strokes = doc.strokes.filter { it.points.isNotEmpty() }
    val out = ArrayList<Float>()
    out += strokes.size.toFloat()
    strokes.forEach { s ->
        val kind = when (s.pen) {
            NotePen.PRESSURE -> GlPen.FOUNTAIN
            NotePen.MARKER -> GlPen.MARKER
            NotePen.HIGHLIGHTER -> GlPen.HIGHLIGHTER
            NotePen.PENCIL -> GlPen.PENCIL
            NotePen.BRUSH -> GlPen.BRUSH
            NotePen.ERASER -> GlPen.ERASER_PIXEL
            else -> GlPen.BALLPOINT
        }
        var a = ((s.argb ushr 24) and 0xFF) / 255f
        if (kind == GlPen.HIGHLIGHTER) a = (a / HIGHLIGHT_ALPHA).coerceAtMost(1f)
        // pixel erase = paint background white (native eraser semantics)
        val (r, g, b) = if (kind == GlPen.ERASER_PIXEL) Triple(1f, 1f, 1f)
        else Triple(((s.argb shr 16) and 0xFF) / 255f, ((s.argb shr 8) and 0xFF) / 255f, (s.argb and 0xFF) / 255f)
        out += listOf(kind.ordinal.toFloat(), r, g, b, if (kind == GlPen.ERASER_PIXEL) 1f else a,
            s.width * scale / widthFactor(kind), s.points.size.toFloat())
        s.points.forEach { p -> out += listOf(p.x * scale, p.y * scale, if (p.hasPressure) p.pressure else 1f) }
    }
    return out.toFloatArray()
}
