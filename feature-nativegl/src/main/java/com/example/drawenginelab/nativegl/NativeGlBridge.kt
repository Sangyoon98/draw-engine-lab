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
}
