package com.example.drawenginelab.ink

import androidx.compose.ui.geometry.Offset
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import com.example.drawenginelab.notefile.InkNoteFile
import com.example.drawenginelab.notefile.NoteDoc
import com.example.drawenginelab.notefile.NoteText

// Ink ops ↔ Ink note file (lossless: brush + encoded StrokeInputBatch) and smartlearning import.

private fun familyName(f: BrushFamily): String = when (f) {
    StockBrushes.marker() -> InkNoteFile.MARKER
    StockBrushes.highlighter() -> InkNoteFile.HIGHLIGHTER
    StockBrushes.dashedLine() -> InkNoteFile.DASHED_LINE
    else -> InkNoteFile.PRESSURE_PEN
}

private fun familyOf(name: String): BrushFamily = when (name) {
    InkNoteFile.MARKER -> StockBrushes.marker()
    InkNoteFile.HIGHLIGHTER -> StockBrushes.highlighter()
    InkNoteFile.DASHED_LINE -> StockBrushes.dashedLine()
    else -> StockBrushes.pressurePen()
}

internal fun InkState.toInkNote(width: Float, height: Float, texts: List<NoteText>): InkNoteFile.Note =
    InkNoteFile.Note(width, height, opList().map { op ->
        when (op) {
            is StrokeOp -> op.stroke.let { s ->
                InkNoteFile.StrokeItem(familyName(s.brush.family), s.brush.colorIntArgb, s.brush.size, s.brush.epsilon, s.inputs)
            }
            is ClearOp -> InkNoteFile.ClearItem(op.width, FloatArray(op.pts.size * 2) { i -> if (i % 2 == 0) op.pts[i / 2].x else op.pts[i / 2].y })
        }
    }, texts)

/** Own file: same-size canvas reuses the decoded inputs as-is; otherwise inputs are rebuilt scaled. */
internal fun InkState.load(note: InkNoteFile.Note, scale: Float, cmPerUnit: Float) {
    if (scale == 1f) {
        replaceAll(note.items.map { item ->
            when (item) {
                is InkNoteFile.StrokeItem ->
                    StrokeOp(Stroke(Brush.createWithColorIntArgb(familyOf(item.family), item.argb, item.size, item.epsilon), item.inputs))
                is InkNoteFile.ClearItem ->
                    ClearOp(List(item.xy.size / 2) { Offset(item.xy[it * 2], item.xy[it * 2 + 1]) }, item.width)
            }
        })
    } else load(InkNoteFile.toNoteDoc(note), scale, cmPerUnit)
}

/**
 * Neutral doc (e.g. smartlearning JSON) → Ink. smartlearning has x/y only, so timing is
 * synthesized and pressure left out; its constant-width "Pen" maps to Ink's marker family.
 * Its eraser strokes become pixel-erase marks, replayed in order like smartlearning's CLEAR paint.
 */
internal fun InkState.load(doc: NoteDoc, scale: Float, cmPerUnit: Float) {
    replaceAll(doc.strokes.filter { it.points.size > 1 }.map { n ->
        if (n.isEraser) {
            ClearOp(n.points.map { Offset(it.x * scale, it.y * scale) }, n.width * scale)
        } else {
            val family = when (n.pen) {
                com.example.drawenginelab.notefile.NotePen.PRESSURE -> StockBrushes.pressurePen()
                com.example.drawenginelab.notefile.NotePen.HIGHLIGHTER -> StockBrushes.highlighter()
                com.example.drawenginelab.notefile.NotePen.DASHED -> StockBrushes.dashedLine()
                else -> StockBrushes.marker()
            }
            StrokeOp(Stroke(Brush.createWithColorIntArgb(family, n.argb, n.width * scale, 0.1f), InkNoteFile.inputsOf(n.points, scale, cmPerUnit)))
        }
    })
}
