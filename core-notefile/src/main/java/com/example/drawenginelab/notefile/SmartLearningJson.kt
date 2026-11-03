package com.example.drawenginelab.notefile

import org.json.JSONArray
import org.json.JSONObject

/**
 * smartlearning's drawing-note format (`DrawNote`, Gson JSON shared with iOS):
 * `{"size":[w,h],"shapes":[{"isEraser":b,"segments":[{"a":[x,y]}],"strokeColor":"#RRGGBBAA","strokeWidth":w,"type":"Pen"}]}`.
 * Points carry x/y only and every pen is "Pen", so pressure, timing, pen kind and text are
 * dropped on write; on read every stroke comes back as a constant-width [NotePen.PEN].
 */
object SmartLearningJson {

    fun write(doc: NoteDoc): String {
        val shapes = JSONArray()
        doc.strokes.forEach { s ->
            val segs = JSONArray()
            s.points.forEach { p ->
                segs.put(JSONObject().put("a", JSONArray().put(p.x.toDouble()).put(p.y.toDouble())))
            }
            shapes.put(
                JSONObject()
                    .put("isEraser", s.isEraser)
                    .put("segments", segs)
                    .put("strokeColor", toRgbaHex(s.argb))
                    .put("strokeWidth", s.width.toDouble())
                    .put("type", "Pen"),
            )
        }
        return JSONObject()
            .put("size", JSONArray().put(doc.width.toDouble()).put(doc.height.toDouble()))
            .put("shapes", shapes)
            .toString()
    }

    fun read(json: String): NoteDoc {
        val root = JSONObject(json)
        val size = root.getJSONArray("size")
        val shapes = root.getJSONArray("shapes")
        val strokes = (0 until shapes.length()).map { i ->
            val o = shapes.getJSONObject(i)
            val segs = o.getJSONArray("segments")
            val pts = (0 until segs.length()).map { k ->
                val a = segs.getJSONObject(k).getJSONArray("a")
                NotePoint(a.getDouble(0).toFloat(), a.getDouble(1).toFloat())
            }
            NoteStroke(
                pen = if (o.optBoolean("isEraser")) NotePen.ERASER else NotePen.PEN,
                argb = parseRgbaHex(o.optString("strokeColor", "#000000FF")),
                width = o.optDouble("strokeWidth", 4.0).toFloat(),
                points = pts,
            )
        }
        return NoteDoc(size.getDouble(0).toFloat(), size.getDouble(1).toFloat(), strokes)
    }

    fun looksLike(bytes: ByteArray): Boolean {
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart()
        return head.startsWith("{") && head.contains("\"size\"") && !head.contains(InkNoteFile.FORMAT)
    }

    // smartlearning stores RGBA ("#RRGGBBAA", iOS order); Android uses ARGB in memory
    fun toRgbaHex(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val rgb = argb and 0xFFFFFF
        return "#%06X%02X".format(rgb, a)
    }

    fun parseRgbaHex(s: String): Int {
        val hex = s.removePrefix("#")
        return when (hex.length) {
            8 -> {
                val rgb = hex.substring(0, 6).toLong(16).toInt()
                val a = hex.substring(6, 8).toInt(16)
                (a shl 24) or rgb
            }
            6 -> (0xFF shl 24) or hex.toLong(16).toInt()
            else -> 0xFF000000.toInt()
        }
    }
}
