package com.example.drawenginelab.nativegl

import android.graphics.Bitmap
import android.graphics.Canvas
import com.example.drawenginelab.core.RichTextCodec
import com.example.drawenginelab.core.richLayout
import kotlin.math.ceil

/**
 * Called back from the native engine (GL thread) to turn a native text object into pixels —
 * our stand-in for Samsung's ObjectTextDrawing, which draws text with Skia into a bitmap.
 * The NDK has no public text/Skia API, so glyphs come from the platform text stack.
 */
object TextRaster {
    @JvmStatic
    fun rasterize(text: String, spans: IntArray, widthPx: Int, scale: Float): Bitmap {
        val layout = richLayout(RichTextCodec.decode(text, spans), widthPx)
        val bmp = Bitmap.createBitmap(
            ceil(widthPx * scale).toInt().coerceAtLeast(1),
            ceil(layout.height * scale).toInt().coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        Canvas(bmp).apply { scale(scale, scale); layout.draw(this) }
        return bmp
    }
}
