package com.example.drawenginelab.notefile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SdocxFileTest {
    private val template = File("src/main/assets/sdocx_template.sdocx")

    /** SDOCX_SAMPLE=<Samsung-exported .sdocx>: parse it and (re)build the stroke-free template asset. */
    @Test
    fun sampleParsesAndBuildsTemplate() {
        val path = System.getenv("SDOCX_SAMPLE")
        assumeTrue(path != null)
        val sample = File(path!!).readBytes()
        val doc = SdocxFile.read(sample)
        println("sample strokes=${doc.strokes.size} pens=${doc.strokes.groupingBy { it.pen }.eachCount()} size=${doc.width}x${doc.height}")
        assertTrue(doc.strokes.isNotEmpty())
        template.writeBytes(SdocxFile.write(NoteDoc(doc.width, doc.height, emptyList()), sample))
    }

    @Test
    fun strokesRoundTrip() {
        assumeTrue(template.exists())
        val pen = NoteStroke(NotePen.PRESSURE, 0xFF1447FF.toInt(), 6f, List(50) { NotePoint(100f + it * 7.3f, 200f + it * 1.1f, 0.5f + it / 200f) })
        val hl = NoteStroke(NotePen.HIGHLIGHTER, 0x73FFCB30, 24f, List(20) { NotePoint(50f + it * 20f, 600f) })
        val out = SdocxFile.write(NoteDoc(1200f, 1600f, listOf(pen, hl)), template.readBytes())
        File("build/roundtrip.sdocx").writeBytes(out)
        val back = SdocxFile.read(out)
        assertEquals(2, back.strokes.size)
        val (p, h) = back.strokes
        assertEquals(NotePen.PRESSURE, p.pen)
        assertEquals(NotePen.HIGHLIGHTER, h.pen)
        assertEquals(0xFF1447FF.toInt(), p.argb)
        assertEquals(0x73FFCB30, h.argb)
        assertEquals(50, p.points.size)
        // pen-name ids must point at note.note's string table (FountainPen = 0, Marker4 = 2 in the template)
        assertEquals(listOf(NotePen.PRESSURE, NotePen.HIGHLIGHTER), back.strokes.map { it.pen })
        // Q10.5 coordinates / Q3.12 pressure: tight, non-accumulating error
        p.points.zip(pen.points).forEach { (a, b) ->
            assertEquals(b.x, a.x, 1f / 32); assertEquals(b.y, a.y, 1f / 32); assertEquals(b.pressure, a.pressure, 1f / 4096)
        }
    }
}
