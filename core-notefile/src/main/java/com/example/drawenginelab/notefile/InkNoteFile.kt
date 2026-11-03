package com.example.drawenginelab.notefile

import android.util.Base64
import androidx.ink.brush.InputToolType
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.hypot

/**
 * Ink note file. Ink has no document format, only per-stroke serialization, so this is the
 * container the guide recommends: per stroke the brush (stock family name, color, size,
 * epsilon) plus `StrokeInputBatch.encode()` (gzip protobuf, base64 here), kept in draw order
 * together with pixel-eraser marks and text boxes.
 */
object InkNoteFile {
    const val FORMAT = "drawenginelab.ink"
    const val EXTENSION = "inknote"

    sealed interface Item
    class StrokeItem(val family: String, val argb: Int, val size: Float, val epsilon: Float, val inputs: StrokeInputBatch) : Item
    class ClearItem(val width: Float, val xy: FloatArray) : Item

    class Note(val width: Float, val height: Float, val items: List<Item>, val texts: List<NoteText>)

    // stock family names (Ink's own StockBrushes; brushes are rebuilt from these on load)
    const val PRESSURE_PEN = "pressurePen"
    const val MARKER = "marker"
    const val HIGHLIGHTER = "highlighter"
    const val DASHED_LINE = "dashedLine"

    fun write(note: Note): ByteArray {
        val items = JSONArray()
        note.items.forEach { item ->
            items.put(
                when (item) {
                    is StrokeItem -> JSONObject()
                        .put("kind", "stroke")
                        .put("family", item.family)
                        .put("color", item.argb)
                        .put("size", item.size.toDouble())
                        .put("epsilon", item.epsilon.toDouble())
                        .put("inputs", Base64.encodeToString(encodeInputs(item.inputs), Base64.NO_WRAP))
                    is ClearItem -> JSONObject()
                        .put("kind", "clear")
                        .put("width", item.width.toDouble())
                        .put("xy", JSONArray().apply { item.xy.forEach { put(it.toDouble()) } })
                },
            )
        }
        val texts = JSONArray()
        note.texts.forEach { t ->
            texts.put(
                JSONObject().put("x", t.x.toDouble()).put("y", t.y.toDouble()).put("w", t.width.toDouble())
                    .put("text", t.text).put("spans", JSONArray().apply { t.spans.forEach { put(it) } }),
            )
        }
        return JSONObject()
            .put("format", FORMAT)
            .put("version", 1)
            .put("size", JSONArray().put(note.width.toDouble()).put(note.height.toDouble()))
            .put("items", items)
            .put("texts", texts)
            .toString()
            .toByteArray(Charsets.UTF_8)
    }

    fun looksLike(bytes: ByteArray): Boolean =
        String(bytes, 0, minOf(bytes.size, 128), Charsets.UTF_8).contains(FORMAT)

    fun read(bytes: ByteArray): Note {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        require(root.optString("format") == FORMAT) { "not an Ink note file" }
        val size = root.getJSONArray("size")
        val arr = root.getJSONArray("items")
        val items = (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            if (o.getString("kind") == "clear") {
                val xy = o.getJSONArray("xy")
                ClearItem(o.getDouble("width").toFloat(), FloatArray(xy.length()) { xy.getDouble(it).toFloat() })
            } else {
                StrokeItem(
                    family = o.getString("family"),
                    argb = o.getInt("color"),
                    size = o.getDouble("size").toFloat(),
                    epsilon = o.getDouble("epsilon").toFloat(),
                    inputs = decodeInputs(Base64.decode(o.getString("inputs"), Base64.NO_WRAP)),
                )
            }
        }
        val ta = root.optJSONArray("texts") ?: JSONArray()
        val texts = (0 until ta.length()).map { i ->
            val o = ta.getJSONObject(i)
            val sp = o.getJSONArray("spans")
            NoteText(
                o.getDouble("x").toFloat(), o.getDouble("y").toFloat(), o.getDouble("w").toFloat(),
                o.getString("text"), IntArray(sp.length()) { sp.getInt(it) },
            )
        }
        return Note(size.getDouble(0).toFloat(), size.getDouble(1).toFloat(), items, texts)
    }

    fun encodeInputs(batch: StrokeInputBatch): ByteArray =
        ByteArrayOutputStream().use { batch.encode(it); it.toByteArray() }

    fun decodeInputs(bytes: ByteArray): ImmutableStrokeInputBatch =
        ByteArrayInputStream(bytes).use { StrokeInputBatch.decode(it) }

    /** Ink note → neutral doc (used by the non-Ink engines to open Ink files). */
    fun toNoteDoc(note: Note): NoteDoc {
        val strokes = note.items.map { item ->
            when (item) {
                is ClearItem -> NoteStroke(
                    NotePen.ERASER, 0xFFFFFFFF.toInt(), item.width,
                    (0 until item.xy.size / 2).map { NotePoint(item.xy[it * 2], item.xy[it * 2 + 1]) },
                )
                is StrokeItem -> {
                    val b = item.inputs
                    val pts = (0 until b.size).map { i ->
                        val p = b.get(i)
                        NotePoint(p.x, p.y, if (p.hasPressure) p.pressure else NotePoint.NO_PRESSURE, p.elapsedTimeMillis)
                    }
                    val pen = when (item.family) {
                        MARKER -> NotePen.MARKER
                        HIGHLIGHTER -> NotePen.HIGHLIGHTER
                        DASHED_LINE -> NotePen.DASHED
                        else -> NotePen.PRESSURE
                    }
                    NoteStroke(pen, item.argb, item.size, pts)
                }
            }
        }
        return NoteDoc(note.width, note.height, strokes, note.texts)
    }

    /**
     * Neutral points → Ink inputs. Ink needs strictly increasing timestamps and one constant
     * stroke unit length; sources without timing (smartlearning) get time synthesized from
     * distance at ~1 px/ms. Pressure is all-or-nothing in a batch.
     */
    fun inputsOf(points: List<NotePoint>, scale: Float, cmPerUnit: Float): ImmutableStrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val withPressure = points.isNotEmpty() && points.all { it.hasPressure }
        val withTime = points.isNotEmpty() && points.all { it.timeMs >= 0 }
        var t = 0L
        points.forEachIndexed { i, p ->
            t = when {
                withTime -> maxOf(p.timeMs, if (i == 0) 0L else t + 1)
                i == 0 -> 0L
                else -> t + maxOf(1L, hypot(p.x - points[i - 1].x, p.y - points[i - 1].y).toLong())
            }
            batch.add(
                StrokeInput.create(
                    p.x * scale, p.y * scale, t, InputToolType.STYLUS, cmPerUnit,
                    if (withPressure) p.pressure.coerceIn(0f, 1f) else StrokeInput.NO_PRESSURE,
                    StrokeInput.NO_TILT, StrokeInput.NO_ORIENTATION,
                ),
            )
        }
        return batch.toImmutable()
    }
}
