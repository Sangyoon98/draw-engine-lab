package com.example.drawenginelab.notefile

/**
 * Engine-neutral note used to move drawings between file formats and engines.
 * Coordinates are canvas pixels at save time ([width] x [height]); [NoteStroke.width] is the
 * drawn (visual) stroke width, so each engine converts it to its own pen base size.
 */
class NoteDoc(
    val width: Float,
    val height: Float,
    val strokes: List<NoteStroke>,
    val texts: List<NoteText> = emptyList(),
) {
    /** Uniform scale onto a canvas of [w] x [h] (keeps aspect; 1 when sizes match or are unknown). */
    fun scaleFor(w: Float, h: Float): Float =
        if (width <= 0f || height <= 0f || w <= 0f || h <= 0f) 1f else minOf(w / width, h / height)
}

class NotePoint(val x: Float, val y: Float, val pressure: Float = NO_PRESSURE, val timeMs: Long = -1L) {
    val hasPressure get() = pressure >= 0f

    companion object { const val NO_PRESSURE = -1f }
}

/** [pen]: one of [NotePen] — the neutral pen vocabulary every engine maps from/to. */
class NoteStroke(val pen: String, val argb: Int, val width: Float, val points: List<NotePoint>) {
    val isEraser get() = pen == NotePen.ERASER
}

object NotePen {
    const val PEN = "pen"             // constant width (smartlearning's only pen)
    const val PRESSURE = "pressure"   // pressure/speed-sensitive pen
    const val MARKER = "marker"
    const val HIGHLIGHTER = "highlighter"
    const val PENCIL = "pencil"
    const val BRUSH = "brush"
    const val DASHED = "dashed"
    const val ERASER = "eraser"       // pixel eraser stroke (replayed in order)
}

/** Rich text box; [spans] uses the core-ui RichTextCodec layout `[type, start, end, value]*`. */
class NoteText(val x: Float, val y: Float, val width: Float, val text: String, val spans: IntArray)
