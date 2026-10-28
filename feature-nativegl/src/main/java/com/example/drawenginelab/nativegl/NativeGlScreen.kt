package com.example.drawenginelab.nativegl

import androidx.compose.foundation.background
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
import com.example.drawenginelab.core.ColorDot
import com.example.drawenginelab.core.PALETTE
import com.example.drawenginelab.core.SIZES
import com.example.drawenginelab.core.SizeDot
import com.example.drawenginelab.core.TitleBar
import com.example.drawenginelab.core.ToolChip

/** Ordinals must match the C++ engine's pen enum (native-lib.cpp). */
enum class GlPen(val label: String) {
    FOUNTAIN("만년필"), BALLPOINT("볼펜"), MARKER("마커"),
    HIGHLIGHTER("형광펜"), PENCIL("연필"), BRUSH("브러시"),
    ERASER_OBJECT("획지우개"), ERASER_PIXEL("픽셀지우개"),
}

@Composable
fun NativeGlScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val view = remember { NativeGlView(context) }
    var pen by remember { mutableStateOf(GlPen.FOUNTAIN) }
    var color by remember { mutableStateOf(PALETTE[0]) }
    var size by remember { mutableStateOf(SIZES[1]) }

    // push tool state into the native engine (also on first composition)
    LaunchedEffect(pen, color, size) {
        view.setPen(pen.ordinal)
        view.setColor(color.red, color.green, color.blue, color.alpha)
        view.setSize(size)
    }

    Column(Modifier.fillMaxSize()) {
        TitleBar("NDK GL 버전 (C++ + GLES · 네이티브)")
        Column(Modifier.fillMaxWidth().background(Color(0xFFF2F2F2)).padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlPen.entries.forEach { p -> ToolChip(p.label, pen == p) { pen = p } }
                Spacer(Modifier.width(8.dp))
                ToolChip("되돌리기", false) { view.undo() }
                ToolChip("전체삭제", false) { view.clearAll() }
                ToolChip("화면맞춤", false) { view.resetView() }
                ToolChip("뒤로", false, onBack)
            }
            Spacer(Modifier.size(6.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PALETTE.forEach { c -> ColorDot(c, selected = c == color) { color = c } }
                Spacer(Modifier.width(10.dp))
                SIZES.forEach { s -> SizeDot(s, selected = s == size, color = color) { size = s } }
            }
        }
        AndroidView(
            factory = { view },
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color.White),
        )
    }
}
