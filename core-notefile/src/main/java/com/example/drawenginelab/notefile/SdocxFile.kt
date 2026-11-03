package com.example.drawenginelab.notefile

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Samsung Notes `.sdocx` (WDoc) reader/writer for strokes — our own code, written from the
 * format facts in the public reverse-engineering notes of github.com/twangodev/sdocx
 * (docs/reverse-engineering/file-format.md) and checked against a file exported by Samsung Notes.
 *
 * Layout: ZIP { note.note, pageIdInfo.dat, <uuid>.page…, media/mediaInfo.dat, end_tag.bin }
 * + the end tag appended after the ZIP. Little-endian. Integrity is a plain SHA-256 chain:
 * object = H(uuid + mtime), layer = H(objects… + H(layer id)), page = H(layers… + H(page id)),
 * pageIdInfo = H(note.note) + per-page hashes.
 *
 * Writing is template-based: note.note / end_tag.bin / media manifest and the page + layer
 * headers come from a real Samsung-exported file, so only the stroke objects and hashes are
 * generated here. Text boxes and eraser strokes are not written (Samsung erases by editing
 * strokes, it has no eraser-stroke replay).
 */
object SdocxFile {
    const val EXTENSION = "sdocx"
    private const val PAGE_SIG = "Page for SAMSUNG S-Pen SDK"

    // Samsung's pen size → drawn width, eyeballed on SM-P610 (fountain draws ~½ of size,
    // Marker4 ~0.7). ponytail: two constants, measure per pen if more pens get mapped.
    private const val FOUNTAIN_SIZE_PER_WIDTH = 2f
    private const val MARKER_SIZE_PER_WIDTH = 1.4f

    fun looksLike(bytes: ByteArray) = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    // ---------------------------------------------------------------- read

    /** Strokes of every page, stacked vertically (page i at y + i × page height). */
    fun read(bytes: ByteArray): NoteDoc {
        val entries = unzip(bytes)
        val note = entries.getValue("note.note")
        val penNames = penNameTable(note)
        val order = pageOrder(entries.getValue("pageIdInfo.dat"))
        val strokes = ArrayList<NoteStroke>()
        var y0 = 0f
        var width = 0f
        order.forEach { uuid ->
            val page = Page.parse(entries.getValue("$uuid.page"))
            width = maxOf(width, page.width.toFloat())
            page.forEachObject { r, type, payload ->
                if (type == 1) decodeStroke(r, payload, penNames, y0)?.let { strokes += it }
            }
            y0 += page.height
        }
        return NoteDoc(width, y0, strokes)
    }

    private fun decodeStroke(r: Reader, payloadAt: Int, penNames: Map<Int, String>, dy: Float): NoteStroke? {
        val f1 = payloadAt + r.i32(payloadAt) // skip base frame (type 0)
        val frameSize = r.i32(f1)
        val flexAt = f1 + r.i32(f1 + 6)
        var p = f1 + 10
        val pml = r.u8(p); val pmask = r.uN(p + 1, pml); p += 1 + pml
        val fml = r.u8(p); val fmask = r.uN(p + 1, fml); p += 1 + fml
        val n = r.u16(p); p += 2
        val compressed = pmask and 1 != 0
        val tilt = pmask and 4 != 0
        val xs = FloatArray(n); val ys = FloatArray(n); val ps = FloatArray(n); val ts = LongArray(n)
        if (n > 0) {
            if (compressed) {
                var x = r.f64(p); var y = r.f64(p + 8); p += 16
                xs[0] = x.toFloat(); ys[0] = y.toFloat()
                for (i in 1 until n) { x += sm(r.u16(p), 32.0); y += sm(r.u16(p + 2), 32.0); xs[i] = x.toFloat(); ys[i] = y.toFloat(); p += 4 }
                var pr = r.f32(p).toDouble(); p += 4; ps[0] = pr.toFloat()
                for (i in 1 until n) { pr += sm(r.u16(p), 4096.0); ps[i] = pr.toFloat(); p += 2 }
                var t = r.i32(p).toLong(); p += 4; ts[0] = t
                for (i in 1 until n) { t += r.u16(p); ts[i] = t; p += 2 }
            } else {
                for (i in 0 until n) { xs[i] = r.f64(p).toFloat(); ys[i] = r.f64(p + 8).toFloat(); p += 16 }
                for (i in 0 until n) { ps[i] = r.f32(p); p += 4 }
                for (i in 0 until n) { ts[i] = r.i32(p).toLong(); p += 4 }
            }
        }
        // flexible style fields, in mask-bit order (only the fixed-size ones we need)
        var q = flexAt
        var argb = 0xFF000000.toInt(); var size = 2f; var penId = -1; var legacyPenId = -1
        for (bit in 0..13) {
            if (fmask and (1 shl bit) == 0) continue
            when (bit) {
                0 -> { legacyPenId = r.i32(q); q += 4 }
                1 -> q += 4
                2 -> { argb = r.i32(q); q += 4 }
                3 -> { size = r.f32(q); q += 4 }
                4 -> q += 1
                7 -> { penId = r.i32(q); q += 4 }
                8, 9, 10, 11, 12, 13 -> q += 4
                else -> break // variable-size field: stop, keep what we have
            }
            if (q > f1 + frameSize) break
        }
        val penName = penNames[if (penId >= 0) penId else legacyPenId].orEmpty()
        val pen = when {
            pmask and 8 != 0 -> return null // eraser-flagged stroke
            "Marker" in penName -> NotePen.HIGHLIGHTER
            "Pencil" in penName -> NotePen.PENCIL
            "Brush" in penName -> NotePen.BRUSH
            "FountainPen" in penName || "InkPen" in penName || "Calligraphy" in penName || "Oblique" in penName -> NotePen.PRESSURE
            else -> NotePen.PEN
        }
        val width = size / if (pen == NotePen.HIGHLIGHTER) MARKER_SIZE_PER_WIDTH else FOUNTAIN_SIZE_PER_WIDTH
        return NoteStroke(pen, argb, width, List(n) { NotePoint(xs[it], ys[it] + dy, ps[it], ts[it]) })
    }

    // sign bit 15 + magnitude in the low 15 bits, fixed-point
    private fun sm(raw: Int, div: Double): Double = (if (raw and 0x8000 != 0) -1 else 1) * (raw and 0x7FFF) / div

    /**
     * note.note string table (pen names): `{u32 id, utf16_u16 text}` records.
     * ponytail: scans for the records instead of walking every optional note field;
     * walk the full field mask if a file ever hides a pen name from this.
     */
    private fun penNameTable(note: ByteArray): Map<Int, String> {
        val r = Reader(note)
        val out = HashMap<Int, String>()
        val needle = "com.samsung".toByteArray(Charsets.UTF_16LE)
        var i = 6
        while (i <= note.size - needle.size) {
            if (note[i] == needle[0] && note.copyOfRange(i, i + needle.size).contentEquals(needle)) {
                val len = r.u16(i - 2)
                if (i + len * 2 <= note.size) out.putIfAbsent(r.i32(i - 6), String(note, i, len * 2, Charsets.UTF_16LE))
                i += needle.size
            } else i++
        }
        return out
    }

    private fun pageOrder(info: ByteArray): List<String> {
        val r = Reader(info)
        var p = 32
        val n = r.u16(p); p += 2
        return List(n) { val (s, next) = r.str16(p); p = next + 32; s }
    }

    // ---------------------------------------------------------------- write

    /**
     * Strokes → `.sdocx`, using [template] (any Samsung-exported .sdocx) for document metadata
     * and page/layer headers. Writes one page holding the strokes plus the template's trailing
     * blank page, mirroring what Samsung itself saves. Coordinates are scaled to page width.
     */
    fun write(doc: NoteDoc, template: ByteArray, nowMicros: Long = System.currentTimeMillis() * 1000): ByteArray {
        val t = unzip(template)
        val note = t.getValue("note.note")
        val pages = pageOrder(t.getValue("pageIdInfo.dat")).map { Page.parse(t.getValue("$it.page")) }
        val proto = pages.last() // header prototype (Samsung's own trailing blank page)
        // the same pen name also appears in note.note's pen-info block with other leading bytes;
        // the string-table record is the low id
        val names = penNameTable(note)
        fun idOf(pen: String) = names.filterValues { pen in it }.keys.minOrNull()
        val fountainId = idOf("FountainPen") ?: 0
        val markerId = idOf("Marker") ?: fountainId

        val scale = if (doc.width > 0f) proto.width / doc.width else 1f
        var clock = nowMicros
        val objects = doc.strokes.filter { !it.isEraser && it.points.size > 1 }.map { s ->
            encodeStroke(s, scale, if (s.pen == NotePen.HIGHLIGHTER) markerId else fountainId, proto, clock++)
        }
        val content = proto.rebuild(UUID.randomUUID().toString(), UUID.randomUUID().toString(), clock++, objects)
        val blank = proto.rebuild(UUID.randomUUID().toString(), UUID.randomUUID().toString(), clock++, emptyList())

        val info = Writer().apply {
            bytes(note.copyOfRange(note.size - 32, note.size)) // = H(note.note body), unchanged note
            u16(2)
            listOf(content, blank).forEach { str16(it.first); bytes(it.third) }
        }.toByteArray()

        val endTag = t.getValue("end_tag.bin")
        val zipped = ByteArrayOutputStream().also { bos ->
            ZipOutputStream(bos).use { z ->
                fun put(name: String, data: ByteArray) { z.putNextEntry(ZipEntry(name)); z.write(data); z.closeEntry() }
                put("pageIdInfo.dat", info)
                put("${content.first}.page", content.second)
                put("${blank.first}.page", blank.second)
                put("media/mediaInfo.dat", t.getValue("media/mediaInfo.dat"))
                put("note.note", note)
                put("end_tag.bin", endTag)
            }
        }.toByteArray()
        return zipped + endTag // native whole-file parser reads the appended copy
    }

    /** One type-1 object record: base frame (type 0) + stroke frame (type 1) + identity hash. */
    private fun encodeStroke(s: NoteStroke, scale: Float, penId: Int, page: Page, mtime: Long): Record {
        val uuid = UUID.randomUUID().toString()
        val n = s.points.size
        val xs = DoubleArray(n) { s.points[it].x * scale.toDouble() }
        val ys = DoubleArray(n) { s.points[it].y * scale.toDouble() }
        val highlighter = s.pen == NotePen.HIGHLIGHTER
        val width = s.width * scale * if (highlighter) MARKER_SIZE_PER_WIDTH else FOUNTAIN_SIZE_PER_WIDTH
        val half = width / 2.0

        val base = Writer().apply {
            i32(121); i16(0); i32(105)
            u8(2); u16(0x01BF)          // property mask as written by Samsung for strokes
            u8(4); i32(0x6000)          // flexible fields 13 (append time) + 14 (owner size)
            i32(page.formatVersion)
            u16(36); bytes(uuid.toByteArray(Charsets.UTF_8))
            i64(mtime)
            f64(xs.min() - half); f64(ys.min() - half); f64(xs.max() + half); f64(ys.max() + half)
            i32(0); u8(0)
            i64(mtime); i32(page.width); i32(page.height)
        }.toByteArray()

        // compressed channels: deltas against the *reconstructed* previous value, so rounding
        // never accumulates (sign bit + Q10.5 coords / Q3.12 pressure); dx, dy interleaved per point
        val pts = Writer().apply {
            u16(n)
            f64(xs[0]); f64(ys[0])
            var rx = xs[0]; var ry = ys[0]
            for (i in 1 until n) {
                rx += delta(xs[i] - rx, 32.0) { u16(it) }
                ry += delta(ys[i] - ry, 32.0) { u16(it) }
            }
            val pr = FloatArray(n) { s.points[it].let { p -> if (p.hasPressure) p.pressure else 1f } }
            f32(pr[0]); var rp = pr[0].toDouble()
            for (i in 1 until n) rp += delta(pr[i] - rp, 4096.0) { u16(it) }
            val ts = synthTimes(s.points)
            i32(ts[0].toInt())
            for (i in 1 until n) u16((ts[i] - ts[i - 1]).coerceIn(0, 0xFFFF).toInt())
            f32(0f); repeat(n - 1) { u16(0) } // tilt
            f32(0f); repeat(n - 1) { u16(0) } // orientation
            u8(2); u8(0)                        // tool/input type bytes as Samsung writes for S Pen
        }.toByteArray()

        val pmask = if (highlighter) 0x465 else 0x425 // compressed+tilt+ms (+top-layer for marker), as Samsung writes
        val fmask = if (highlighter) 0x248E else 0x258E
        val style = Writer().apply {
            i32(penId + 1)                                   // advanced-setting string id (template pairs name id → id + 1)
            i32(s.argb)
            f32(width)
            i32(penId)
            if (!highlighter) f32(width)                    // fixed width (bit 8)
            i32(1)                                           // particle density
            f32(if (highlighter) 1.7f else 2.1666667f)       // initial tolerance
        }.toByteArray()
        val headerLen = 4 + 2 + 4 + 1 + 2 + 1 + 4
        val strokeFrame = Writer().apply {
            i32(headerLen + pts.size + style.size); i16(1); i32(headerLen + pts.size)
            u8(2); u16(pmask); u8(4); i32(fmask)
            bytes(pts); bytes(style)
        }.toByteArray()

        val hash = identity(uuid, mtime)
        val rec = Writer().apply {
            u8(1); u16(0); i32(base.size + strokeFrame.size + 32)
            bytes(base); bytes(strokeFrame); bytes(hash)
        }.toByteArray()
        return Record(rec, hash)
    }

    private inline fun delta(d: Double, div: Double, emit: (Int) -> Unit): Double {
        val mag = (abs(d) * div).roundToInt().coerceAtMost(0x7FFF)
        emit(if (d < 0 && mag != 0) mag or 0x8000 else mag)
        return (if (d < 0) -mag else mag) / div
    }

    private fun synthTimes(points: List<NotePoint>): LongArray {
        if (points.all { it.timeMs >= 0 }) {
            val t0 = points[0].timeMs
            return LongArray(points.size) { points[it].timeMs - t0 }
        }
        val out = LongArray(points.size)
        for (i in 1 until points.size) {
            val d = kotlin.math.hypot((points[i].x - points[i - 1].x).toDouble(), (points[i].y - points[i - 1].y).toDouble())
            out[i] = out[i - 1] + maxOf(1L, d.toLong() / 2) // ~2 px/ms
        }
        return out
    }

    internal fun identity(id: String, mtime: Long): ByteArray = sha256((id + mtime.toString()).toByteArray(Charsets.UTF_8))
    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").apply { parts.forEach { update(it) } }.digest()

    class Record(val bytes: ByteArray, val hash: ByteArray)

    /** A parsed page: header bytes (reused verbatim as a prototype) + its layer. */
    private class Page(
        val raw: ByteArray, val layerOffset: Int, val uuidAt: Int, val width: Int, val height: Int,
        val formatVersion: Int, val layerStart: Int, val layerHeaderSize: Int, val layerUuidAt: Int,
    ) {
        /** Visits top-level and child objects of the current layer: (reader, type, payload offset). */
        fun forEachObject(visit: (Reader, Int, Int) -> Unit) {
            val r = Reader(raw)
            var p = layerStart + layerHeaderSize
            val count = r.i32(p); p += 4
            fun obj(at: Int): Int {
                val type = r.u8(at); val children = r.u16(at + 1); val size = r.i32(at + 3)
                visit(r, type, at + 7)
                var next = at + 7 + size
                repeat(children) { next = obj(next) }
                return next
            }
            repeat(count) { p = obj(p) }
        }

        /** New page from this one's headers: fresh page/layer ids + times, given objects, new hashes. */
        fun rebuild(pageUuid: String, layerUuid: String, mtime: Long, objects: List<Record>): Triple<String, ByteArray, ByteArray> {
            val head = raw.copyOfRange(0, layerOffset)
            putStr(head, uuidAt, pageUuid); putI64(head, uuidAt + 2 + 72, mtime)
            val layerHead = raw.copyOfRange(layerStart, layerStart + layerHeaderSize)
            val lu = layerUuidAt - layerStart
            putStr(layerHead, lu, layerUuid); putI64(layerHead, lu + 2 + 72, mtime)

            val layerHash = sha256(*objects.map { it.hash }.toTypedArray(), identity(layerUuid, mtime))
            val pageHash = sha256(layerHash, identity(pageUuid, mtime))
            val body = Writer().apply {
                bytes(head)
                u16(1); u16(0)
                bytes(layerHead)
                i32(objects.size)
                objects.forEach { bytes(it.bytes) }
                bytes(layerHash)
                bytes(pageHash)
                bytes(PAGE_SIG.toByteArray(Charsets.US_ASCII))
            }.toByteArray()
            return Triple(pageUuid, body, pageHash)
        }

        companion object {
            fun parse(b: ByteArray): Page {
                val r = Reader(b)
                val layerOffset = r.i32(0)
                var p = 8
                p += 1 + r.u8(p)
                p += 1 + r.u8(p)
                p += 4 // orientation
                val w = r.i32(p); val h = r.i32(p + 4); p += 16
                val uuidAt = p
                val (_, afterUuid) = r.str16(p)
                val fmt = r.i32(afterUuid + 8)
                val layerStart = layerOffset + 4
                val layerHeaderSize = r.i32(layerStart)
                // layer flexible area (absolute offset) starts with uuid then mtime — Samsung's
                // layer field mask 0x18; other masks would shift them
                val layerFlexAbs = r.i32(layerStart + 4)
                return Page(b, layerOffset, uuidAt, w, h, fmt, layerStart, layerHeaderSize, layerFlexAbs)
            }

            private fun putStr(b: ByteArray, at: Int, s: String) {
                require(s.length == 36) // same length as Samsung's ids, so offsets stay valid
                ByteBuffer.wrap(b, at, 2 + 72).order(ByteOrder.LITTLE_ENDIAN).apply {
                    putShort(36)
                    s.forEach { putChar(it) }
                }
            }

            private fun putI64(b: ByteArray, at: Int, v: Long) {
                ByteBuffer.wrap(b, at, 8).order(ByteOrder.LITTLE_ENDIAN).putLong(v)
            }
        }
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) { val e = z.nextEntry ?: break; if (!e.isDirectory) out[e.name] = z.readBytes() }
        }
        return out
    }

    private class Reader(val b: ByteArray) {
        private fun bb(at: Int, n: Int) = ByteBuffer.wrap(b, at, n).order(ByteOrder.LITTLE_ENDIAN)
        fun u8(at: Int) = b[at].toInt() and 0xFF
        fun u16(at: Int) = bb(at, 2).short.toInt() and 0xFFFF
        fun i32(at: Int) = bb(at, 4).int
        fun f32(at: Int) = bb(at, 4).float
        fun f64(at: Int) = bb(at, 8).double
        fun uN(at: Int, n: Int): Int { var v = 0; for (i in 0 until minOf(n, 4)) v = v or (u8(at + i) shl (8 * i)); return v }
        fun str16(at: Int): Pair<String, Int> { val n = u16(at); return String(b, at + 2, n * 2, Charsets.UTF_16LE) to at + 2 + n * 2 }
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        private val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        private fun put(n: Int) { out.write(buf.array(), 0, n); buf.clear() }
        fun u8(v: Int) = out.write(v)
        fun u16(v: Int) { buf.putShort(v.toShort()); put(2) }
        fun i16(v: Int) = u16(v)
        fun i32(v: Int) { buf.putInt(v); put(4) }
        fun i64(v: Long) { buf.putLong(v); put(8) }
        fun f32(v: Float) { buf.putFloat(v); put(4) }
        fun f64(v: Double) { buf.putDouble(v); put(8) }
        fun bytes(b: ByteArray) = out.write(b)
        fun str16(s: String) { u16(s.length); s.toByteArray(Charsets.UTF_16LE).let(::bytes) }
        fun toByteArray(): ByteArray = out.toByteArray()
    }
}
