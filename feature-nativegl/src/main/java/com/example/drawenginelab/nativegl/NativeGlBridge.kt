package com.example.drawenginelab.nativegl

/** JNI boundary to the native C++/GLES engine (libnativegl.so). */
object NativeGlBridge {
    init { System.loadLibrary("nativegl") }

    external fun onSurfaceCreated()
    external fun onSurfaceChanged(w: Int, h: Int)
    external fun onDrawFrame()
    external fun setPen(pen: Int)
    external fun setColor(r: Float, g: Float, b: Float, a: Float)
    external fun setSize(size: Float)
    external fun setView(scale: Float, offX: Float, offY: Float)
    external fun resetView()
    external fun down(x: Float, y: Float, p: Float)
    external fun move(x: Float, y: Float, p: Float)
    external fun up()
    external fun undo()
    external fun clearAll()

    // native text objects (world units; thread-safe on the native side)
    external fun textAdd(x: Float, y: Float, w: Float, h: Float, text: String, spans: IntArray): Int
    external fun textUpdate(id: Int, h: Float, text: String, spans: IntArray)
    external fun textMove(id: Int, x: Float, y: Float)
    external fun textRemove(id: Int)
    external fun textSetHidden(id: Int)
    external fun textHit(x: Float, y: Float): Int
    external fun textGet(id: Int): String
    external fun textSpans(id: Int): IntArray
    external fun textRect(id: Int): FloatArray
}
