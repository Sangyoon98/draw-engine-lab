package com.example.drawenginelab.nativegl

import android.text.SpannableStringBuilder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.drawenginelab.core.PenOptionsRow
import com.example.drawenginelab.core.RichTextCodec
import com.example.drawenginelab.core.RichTextEditController
import com.example.drawenginelab.core.RichTextEditor
import com.example.drawenginelab.core.TEXT_BOX_WIDTH
import com.example.drawenginelab.core.TextFormatBar
import com.example.drawenginelab.core.richLayout
import com.example.drawenginelab.core.PALETTE
import com.example.drawenginelab.core.TitleBar
import com.example.drawenginelab.core.ToolChip

/** Ordinals must match the C++ engine's pen enum (native-lib.cpp). */
enum class GlPen(val label: String) {
    FOUNTAIN("만년필"), BALLPOINT("볼펜"), MARKER("마커"),
    HIGHLIGHTER("형광펜"), PENCIL("연필"), BRUSH("브러시"),
    ERASER_OBJECT("획지우개"), ERASER_PIXEL("픽셀지우개"),
}

/** A box open in the Java editor; the model itself lives in the native engine (id -1 = new). */
private class NdkEdit(val id: Int, val x: Float, val y: Float, val w: Int, val text: CharSequence)

@Composable
fun NativeGlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = remember { NativeGlView(context) }
    var pen by remember { mutableStateOf(GlPen.FOUNTAIN) }
    var color by remember { mutableStateOf(PALETTE[0]) }
    var size by remember { mutableStateOf(8f) }
    var textMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<NdkEdit?>(null) }
    val editor = remember { RichTextEditController() }
    var viewT by remember { mutableStateOf(floatArrayOf(1f, 0f, 0f)) }

    // push tool state into the native engine (also on first composition)
    LaunchedEffect(pen, color, size) {
        view.setPen(pen.ordinal)
        view.setColor(color.red, color.green, color.blue, color.alpha)
        view.setSize(size)
    }

    // Editor → native object: text + flat span list go over JNI; the engine owns the model.
    fun commit() {
        val ed = editing ?: return
        val text = editor.snapshot() ?: SpannableStringBuilder(ed.text)
        val str = text.toString()
        when {
            str.isBlank() -> if (ed.id >= 0) NativeGlBridge.textRemove(ed.id)
            else -> {
                val h = richLayout(text, ed.w).height.toFloat()
                val spans = RichTextCodec.encode(text)
                if (ed.id >= 0) NativeGlBridge.textUpdate(ed.id, h, str, spans)
                else NativeGlBridge.textAdd(ed.x, ed.y, ed.w.toFloat(), h, str, spans)
            }
        }
        NativeGlBridge.textSetHidden(-1)
        editing = null
        view.requestRender()
    }

    fun deleteEditing() {
        editing?.let { if (it.id >= 0) NativeGlBridge.textRemove(it.id) }
        NativeGlBridge.textSetHidden(-1)
        editing = null
        view.requestRender()
    }

    SideEffect {
        view.textMode = textMode
        view.onViewChanged = { s, x, y -> viewT = floatArrayOf(s, x, y) }
        view.onTextTap = { id, wx, wy ->
            commit()
            val ed = if (id >= 0) {
                val r = NativeGlBridge.textRect(id)
                NdkEdit(id, r[0], r[1], r[2].toInt(), RichTextCodec.decode(NativeGlBridge.textGet(id), NativeGlBridge.textSpans(id)))
            } else NdkEdit(-1, wx, wy, TEXT_BOX_WIDTH, "")
            NativeGlBridge.textSetHidden(ed.id) // the Java editor shows it while open
            editing = ed
            view.requestRender()
        }
    }
    LaunchedEffect(textMode) { if (!textMode) commit() }

    Column(Modifier.fillMaxSize()) {
        TitleBar("NDK GL 버전 (C++ + GLES · 네이티브)")
        Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlPen.entries.forEach { p -> ToolChip(p.label, !textMode && pen == p) { pen = p; textMode = false } }
                ToolChip("텍스트", textMode) { textMode = true }
                Spacer(Modifier.width(8.dp))
                ToolChip("되돌리기", false) { view.undo() }
                ToolChip("전체삭제", false) { editing = null; NativeGlBridge.textSetHidden(-1); view.clearAll() }
                ToolChip("화면맞춤", false) { view.resetView() }
                ToolChip("뒤로", false, onBack)
            }
            Spacer(Modifier.size(6.dp))
            PenOptionsRow(color, size, { color = it }, { size = it })
            if (textMode) TextFormatBar(editor, color, onDone = ::commit, onDelete = ::deleteEditing)
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { view }, modifier = Modifier.fillMaxSize().background(Color.White))
            editing?.let { ed ->
                val (s, ox, oy) = viewT
                key(ed) {
                    RichTextEditor(
                        editor, ed.text, ed.w,
                        Modifier
                            .offset { IntOffset((ed.x * s + ox).roundToInt(), (ed.y * s + oy).roundToInt()) }
                            .graphicsLayer { scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0f) },
                    )
                }
            }
        }
    }
}
