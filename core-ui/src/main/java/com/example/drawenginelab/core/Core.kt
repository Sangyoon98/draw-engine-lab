package com.example.drawenginelab.core

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.lang.reflect.Proxy
import kotlin.math.abs

// --- shared palette + thickness presets --------------------------------------

val PALETTE = listOf(
    Color.Black,
    Color(0xFFE53935),
    Color(0xFF1E88E5),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
)
val SIZES = listOf(4f, 8f, 16f, 28f)

// --- shared toolbar atoms -----------------------------------------------------

@Composable
fun ToolChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Text(label, fontSize = 12.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = ButtonDefaults.TextButtonContentPadding) {
            Text(label, fontSize = 12.sp)
        }
    }
}

@Composable
fun ColorDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color(0xFF1565C0) else Color(0xFFBBBBBB),
                shape = CircleShape,
            )
            .clickable { onClick() },
    )
}

@Composable
fun SizeDot(size: Float, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) Color(0xFF1565C0) else Color(0xFFBBBBBB),
                shape = CircleShape,
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size((size.coerceAtMost(22f)).dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

/**
 * Shared color/size row for all three engines: palette + hue spectrum, opacity and a
 * continuous width slider. Opacity rides in [color]'s alpha, so engines just honor alpha.
 */
@Composable
fun PenOptionsRow(color: Color, size: Float, onColor: (Color) -> Unit, onSize: (Float) -> Unit) {
    val opaque = color.copy(alpha = 1f)
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PALETTE.forEach { c -> ColorDot(c, selected = c == opaque) { onColor(c.copy(alpha = color.alpha)) } }
        HueBar { onColor(it.copy(alpha = color.alpha)) }
        Text("투명도", fontSize = 12.sp)
        Slider(
            value = color.alpha, onValueChange = { onColor(opaque.copy(alpha = it)) },
            valueRange = 0.1f..1f, modifier = Modifier.width(120.dp),
        )
        Text("굵기 ${size.toInt()}", fontSize = 12.sp)
        Slider(value = size, onValueChange = onSize, valueRange = 2f..40f, modifier = Modifier.width(160.dp))
        SizeDot(size, selected = true, color = color) {}
    }
}

private val HUES = List(7) { Color.hsv(it * 60f % 360f, 1f, 0.9f) }

/** Tap/drag along the bar to pick a hue (full saturation). */
@Composable
private fun HueBar(onPick: (Color) -> Unit) {
    val pick by rememberUpdatedState(onPick)
    Box(
        Modifier
            .size(width = 160.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(HUES))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun emit(x: Float) = pick(Color.hsv((x / size.width).coerceIn(0f, 1f) * 359f, 1f, 0.9f))
                    emit(down.position.x)
                    do {
                        val e = awaitPointerEvent()
                        e.changes.firstOrNull()?.let { emit(it.position.x); it.consume() }
                    } while (e.changes.any { it.pressed })
                }
            },
    )
}

@Composable
fun TitleBar(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
        textAlign = TextAlign.Center,
        fontSize = 13.sp,
    )
}

/** Debounced air-gesture → undo (left) / redo (right). */
fun handleAir(gate: LongArray, dx: Float, onUndo: () -> Unit, onRedo: () -> Unit) {
    val now = System.currentTimeMillis()
    if (now - gate[0] > 350 && abs(dx) > 1.5f) {
        gate[0] = now
        if (dx < 0) onUndo() else onRedo()
    }
}

// --- pan/zoom ----------------------------------------------------------------

class CanvasTransform {
    var scale by mutableStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    fun apply(newScale: Float, newOffset: Offset) {
        scale = newScale
        offset = newOffset
    }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }
}

@Composable
fun ZoomPanBox(
    transform: CanvasTransform,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clipToBounds()
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val newScale = (transform.scale * zoom).coerceIn(0.5f, 6f)
                            val newOffset =
                                centroid - (centroid - transform.offset) * (newScale / transform.scale) + pan
                            transform.apply(newScale, newOffset)
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .graphicsLayer {
                scaleX = transform.scale
                scaleY = transform.scale
                translationX = transform.offset.x
                translationY = transform.offset.y
                transformOrigin = TransformOrigin(0f, 0f)
            },
    ) {
        content()
    }
}

// --- Samsung S Pen Remote SDK (reflection; build-safe without the AAR) --------

class SPenRemoteController(
    private val onButtonClick: () -> Unit,
    private val onAirMotion: (dx: Float, dy: Float) -> Unit,
    private val onStatus: (Boolean) -> Unit = {},
) {
    private val pkg = "com.samsung.android.sdk.penremote"
    private var remote: Any? = null

    val isSdkPresent: Boolean =
        try { Class.forName("$pkg.SpenRemote"); true } catch (_: Throwable) { false }

    fun connect(context: Context) {
        if (!isSdkPresent) { onStatus(false); return }
        try {
            val remoteCls = Class.forName("$pkg.SpenRemote")
            val instance = remoteCls.getMethod("getInstance").invoke(null)
            val cbCls = Class.forName("$pkg.SpenRemote\$ConnectionResultCallback")
            val cb = Proxy.newProxyInstance(context.classLoader, arrayOf(cbCls)) { _, method, args ->
                if (method.name == "onSuccess") registerUnits(context, args?.getOrNull(0))
                else onStatus(false)
                null
            }
            remoteCls.getMethod("connect", Context::class.java, cbCls).invoke(instance, context, cb)
            remote = instance
        } catch (t: Throwable) {
            Log.w(TAG, "connect failed: ${t.message}")
            onStatus(false)
        }
    }

    private fun registerUnits(context: Context, unitManager: Any?) {
        unitManager ?: return
        try {
            val remoteCls = Class.forName("$pkg.SpenRemote")
            val featButton = remoteCls.getField("FEATURE_TYPE_BUTTON").getInt(null)
            val featAir = remoteCls.getField("FEATURE_TYPE_AIR_MOTION").getInt(null)
            val umCls = Class.forName("$pkg.SpenUnitManager")
            val getUnit = umCls.getMethod("getUnit", Int::class.javaPrimitiveType)
            val listenerCls = Class.forName("$pkg.SpenEventListener")
            val register = umCls.getMethod("registerSpenEventListener", listenerCls, Class.forName("$pkg.SpenUnit"))
            getUnit.invoke(unitManager, featButton)?.let { unit ->
                register.invoke(unitManager, listenerProxy(context, listenerCls, ::handleButton), unit)
            }
            getUnit.invoke(unitManager, featAir)?.let { unit ->
                register.invoke(unitManager, listenerProxy(context, listenerCls, ::handleAirEvent), unit)
            }
            onStatus(true)
        } catch (t: Throwable) {
            Log.w(TAG, "register failed: ${t.message}")
            onStatus(false)
        }
    }

    private fun listenerProxy(context: Context, listenerCls: Class<*>, handler: (Any?) -> Unit): Any =
        Proxy.newProxyInstance(context.classLoader, arrayOf(listenerCls)) { _, method, args ->
            if (method.name == "onEvent") handler(args?.getOrNull(0))
            null
        }

    private fun handleButton(event: Any?) {
        event ?: return
        try {
            val btnCls = Class.forName("$pkg.ButtonEvent")
            val be = btnCls.getConstructor(Class.forName("$pkg.SpenEvent")).newInstance(event)
            val action = btnCls.getMethod("getAction").invoke(be) as Int
            val actionUp = btnCls.getField("ACTION_UP").getInt(null)
            if (action == actionUp) onButtonClick()
        } catch (t: Throwable) {
            Log.w(TAG, "button parse failed: ${t.message}")
        }
    }

    private fun handleAirEvent(event: Any?) {
        event ?: return
        try {
            val airCls = Class.forName("$pkg.AirMotionEvent")
            val ae = airCls.getConstructor(Class.forName("$pkg.SpenEvent")).newInstance(event)
            val dx = airCls.getMethod("getDeltaX").invoke(ae) as Float
            val dy = airCls.getMethod("getDeltaY").invoke(ae) as Float
            onAirMotion(dx, dy)
        } catch (t: Throwable) {
            Log.w(TAG, "air parse failed: ${t.message}")
        }
    }

    fun disconnect(context: Context) {
        val r = remote ?: return
        try {
            Class.forName("$pkg.SpenRemote").getMethod("disconnect", Context::class.java).invoke(r, context)
        } catch (_: Throwable) {
        }
        remote = null
    }

    private companion object { const val TAG = "SPenRemote" }
}

@Composable
fun rememberSPen(onButton: () -> Unit, onAir: (Float, Float) -> Unit): Boolean {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(false) }
    val curButton by rememberUpdatedState(onButton)
    val curAir by rememberUpdatedState(onAir)

    DisposableEffect(Unit) {
        val controller = SPenRemoteController(
            onButtonClick = { curButton() },
            onAirMotion = { dx, dy -> curAir(dx, dy) },
            onStatus = { connected = it },
        )
        controller.connect(context)
        onDispose { controller.disconnect(context) }
    }
    return connected
}
