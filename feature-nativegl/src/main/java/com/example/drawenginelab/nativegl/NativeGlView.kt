package com.example.drawenginelab.nativegl

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.opengles.GL10

/**
 * The thin Java/Kotlin layer, exactly like Samsung's: it owns the GL surface +
 * context and forwards touch to the native engine over JNI. All stroke geometry
 * and rendering happen in C++ ([NativeGlBridge] → libnativegl.so).
 *
 * Touch runs on the UI thread, GL on the render thread, so native calls are posted
 * to the GL thread with [queueEvent].
 */
class NativeGlView(context: Context) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MsaaConfigChooser()) // 4x MSAA for smooth stroke edges
        setRenderer(Renderer())
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    /** Pick an ES2 config with 4x multisampling; fall back to a plain config if unavailable. */
    private class MsaaConfigChooser : GLSurfaceView.EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val msaa = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4,
                EGL10.EGL_NONE,
            )
            val num = IntArray(1)
            if (egl.eglChooseConfig(display, msaa, null, 0, num) && num[0] > 0) {
                val configs = arrayOfNulls<EGLConfig>(num[0])
                egl.eglChooseConfig(display, msaa, configs, num[0], num)
                return configs[0]!!
            }
            val plain = intArrayOf(
                EGL10.EGL_RENDERABLE_TYPE, 4,
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_NONE,
            )
            egl.eglChooseConfig(display, plain, null, 0, num)
            val configs = arrayOfNulls<EGLConfig>(num[0])
            egl.eglChooseConfig(display, plain, configs, num[0], num)
            return configs[0]!!
        }
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = NativeGlBridge.onSurfaceCreated()
        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = NativeGlBridge.onSurfaceChanged(width, height)
        override fun onDrawFrame(gl: GL10?) = NativeGlBridge.onDrawFrame()
    }

    // view pan/zoom (mirrors the native u_scale/u_off); 1 finger draws, 2 fingers transform
    private var mode = 0 // 0 none, 1 draw, 2 transform
    var vScale = 1f
        private set
    var vOffX = 0f
        private set
    var vOffY = 0f
        private set

    /** Fires (scale, offX, offY) whenever the view transform changes, so overlays can follow. */
    var onViewChanged: ((Float, Float, Float) -> Unit)? = null

    // text mode: one finger taps (open/create a box) or drags a box; strokes are not drawn
    var textMode = false
    /** (hit text id or -1, world x, world y) on a tap in text mode. */
    var onTextTap: ((Int, Float, Float) -> Unit)? = null
    private var textHitId = -1
    private var textDragged = false
    private var downX = 0f
    private var downY = 0f
    private var lastWx = 0f
    private var lastWy = 0f

    private fun worldX(x: Float) = (x - vOffX) / vScale
    private fun worldY(y: Float) = (y - vOffY) / vScale
    private var prevSpan = 0f
    private var prevFx = 0f
    private var prevFy = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 1
                if (textMode) {
                    downX = e.x; downY = e.y
                    lastWx = worldX(e.x); lastWy = worldY(e.y)
                    textHitId = NativeGlBridge.textHit(lastWx, lastWy)
                    textDragged = false
                } else {
                    val x = e.x; val y = e.y; val p = e.pressure
                    queueEvent { NativeGlBridge.down(x, y, p) }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    if (mode == 1 && !textMode) queueEvent { NativeGlBridge.up() } // abandon the just-started stroke
                    mode = 2
                    prevSpan = span(e)
                    val f = focus(e); prevFx = f[0]; prevFy = f[1]
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 2 && e.pointerCount >= 2) {
                    val span = span(e)
                    val f = focus(e); val fx = f[0]; val fy = f[1]
                    if (prevSpan > 0f) {
                        vOffX += fx - prevFx; vOffY += fy - prevFy // pan
                        val ns = (vScale * (span / prevSpan)).coerceIn(0.5f, 6f)
                        val k = ns / vScale // zoom about focus
                        vOffX = fx - (fx - vOffX) * k
                        vOffY = fy - (fy - vOffY) * k
                        vScale = ns
                        val s = vScale; val ox = vOffX; val oy = vOffY
                        queueEvent { NativeGlBridge.setView(s, ox, oy) }
                        onViewChanged?.invoke(s, ox, oy)
                    }
                    prevSpan = span; prevFx = fx; prevFy = fy
                } else if (mode == 1 && textMode) {
                    if (textHitId >= 0 && (textDragged || kotlin.math.hypot(e.x - downX, e.y - downY) > 12f)) {
                        textDragged = true
                        val wx = worldX(e.x); val wy = worldY(e.y)
                        val r = NativeGlBridge.textRect(textHitId)
                        NativeGlBridge.textMove(textHitId, r[0] + wx - lastWx, r[1] + wy - lastWy)
                        lastWx = wx; lastWy = wy
                    }
                } else if (mode == 1) {
                    val batch = ArrayList<FloatArray>(e.historySize + 1)
                    for (h in 0 until e.historySize) {
                        batch.add(floatArrayOf(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h)))
                    }
                    batch.add(floatArrayOf(e.x, e.y, e.pressure))
                    queueEvent { batch.forEach { NativeGlBridge.move(it[0], it[1], it[2]) } }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (e.pointerCount <= 2) mode = 0 // leaving 2-finger; don't resume drawing this gesture
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mode == 1 && textMode) {
                    if (!textDragged && e.actionMasked == MotionEvent.ACTION_UP) onTextTap?.invoke(textHitId, worldX(e.x), worldY(e.y))
                } else if (mode == 1) queueEvent { NativeGlBridge.up() }
                mode = 0
            }
        }
        requestRender()
        return true
    }

    private fun span(e: MotionEvent): Float =
        kotlin.math.hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun focus(e: MotionEvent): FloatArray =
        floatArrayOf((e.getX(0) + e.getX(1)) / 2f, (e.getY(0) + e.getY(1)) / 2f)

    fun setPen(pen: Int) { queueEvent { NativeGlBridge.setPen(pen) } }
    fun setColor(r: Float, g: Float, b: Float, a: Float) { queueEvent { NativeGlBridge.setColor(r, g, b, a) } }
    fun setSize(size: Float) { queueEvent { NativeGlBridge.setSize(size) } }
    fun undo() { queueEvent { NativeGlBridge.undo() }; requestRender() }
    fun clearAll() { queueEvent { NativeGlBridge.clearAll() }; requestRender() }
    fun resetView() {
        vScale = 1f; vOffX = 0f; vOffY = 0f
        queueEvent { NativeGlBridge.resetView() }
        onViewChanged?.invoke(1f, 0f, 0f)
        requestRender()
    }
}
