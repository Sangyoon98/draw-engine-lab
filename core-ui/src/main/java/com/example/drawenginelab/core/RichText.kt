package com.example.drawenginelab.core

import android.graphics.Typeface
import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.AlignmentSpan
import android.text.style.BulletSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Gravity
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.hypot
import kotlin.math.roundToInt

// --- rich-text model (Samsung: RichTextSpan + *Paragraph lists in libSPenDrawing) -------

const val TEXT_BASE_PX = 40f
const val TEXT_BOX_WIDTH = 640

/**
 * Flat span codec: `[type, start, end, value]*`. Lets rich text cross JNI as plain data so
 * the NDK engine can own the text model natively, the way Samsung keeps its span lists in C++.
 * Only the span kinds the editor creates are kept; IME/spell-check spans are dropped.
 */
object RichTextCodec {
    const val BOLD = 1
    const val ITALIC = 2
    const val UNDERLINE = 3
    const val SIZE = 4
    const val COLOR = 5
    const val ALIGN = 6
    const val BULLET = 7

    fun typeOf(span: Any): Int = when (span) {
        is StyleSpan -> when (span.style) { Typeface.BOLD -> BOLD; Typeface.ITALIC -> ITALIC; else -> 0 }
        is UnderlineSpan -> UNDERLINE
        is AbsoluteSizeSpan -> SIZE
        is ForegroundColorSpan -> COLOR
        is AlignmentSpan -> ALIGN
        is BulletSpan -> BULLET
        else -> 0
    }

    fun valueOf(span: Any): Int = when (span) {
        is AbsoluteSizeSpan -> span.size
        is ForegroundColorSpan -> span.foregroundColor
        is AlignmentSpan -> span.alignment.ordinal
        else -> 0
    }

    fun make(type: Int, value: Int): Any = when (type) {
        BOLD -> StyleSpan(Typeface.BOLD)
        ITALIC -> StyleSpan(Typeface.ITALIC)
        UNDERLINE -> UnderlineSpan()
        SIZE -> AbsoluteSizeSpan(value)
        COLOR -> ForegroundColorSpan(value)
        ALIGN -> AlignmentSpan.Standard(Layout.Alignment.entries[value])
        else -> if (Build.VERSION.SDK_INT >= 28) BulletSpan(24, android.graphics.Color.DKGRAY, 7) else BulletSpan(24)
    }

    fun isParagraph(type: Int) = type == ALIGN || type == BULLET

    fun encode(s: Spanned): IntArray {
        val out = ArrayList<Int>()
        for (span in s.getSpans(0, s.length, Any::class.java)) {
            val t = typeOf(span)
            if (t == 0) continue
            out += t; out += s.getSpanStart(span); out += s.getSpanEnd(span); out += valueOf(span)
        }
        return out.toIntArray()
    }

    fun decode(text: String, spans: IntArray): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        for (i in spans.indices step 4) {
            val (t, a, b, v) = listOf(spans[i], spans[i + 1], spans[i + 2], spans[i + 3])
            if (a < 0 || b > sb.length || a > b) continue
            sb.setSpan(make(t, v), a, b, flagsFor(t, a, b))
        }
        return sb
    }

    // paragraph styles keep going onto new lines (bullet list continues on Enter);
    // char styles extend while typing at their end; zero-length spans must be inclusive.
    fun flagsFor(type: Int, a: Int, b: Int): Int =
        if (isParagraph(type) || a == b) Spanned.SPAN_INCLUSIVE_INCLUSIVE else Spanned.SPAN_EXCLUSIVE_INCLUSIVE
}

/** Lays rich text out with the platform text stack (glyph shaping, IME-safe, Hangul). */
fun richLayout(text: CharSequence, widthPx: Int): StaticLayout {
    val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = TEXT_BASE_PX
        color = android.graphics.Color.BLACK
    }
    return StaticLayout.Builder.obtain(text, 0, text.length, paint, widthPx.coerceAtLeast(1)).build()
}

// --- editor (Samsung: the editing box lives in the Java layer, model goes native) -------

/** Holds the live EditText while a box is edited, so a toolbar outside the zoom area can format it. */
class RichTextEditController {
    var editText: EditText? by mutableStateOf(null)
        internal set

    /** Snapshot of the edited text with IME composing spans stripped. */
    fun snapshot(): SpannableStringBuilder? {
        val et = editText ?: return null
        BaseInputConnection.removeComposingSpans(et.text)
        et.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(et.windowToken, 0)
        return SpannableStringBuilder(et.text)
    }

    fun toggle(type: Int) = applyChar(type, 0, toggle = true)
    fun setSize(px: Int) = applyChar(RichTextCodec.SIZE, px, toggle = false)
    fun setColor(color: Color) = applyChar(RichTextCodec.COLOR, color.toArgb(), toggle = false)

    /**
     * Applies a character style to the selection, or — with an empty selection — sets the
     * "typing style" at the cursor via a zero-length inclusive span. Existing spans of the
     * same type are split around the range; a toggle turns off when the range is already covered.
     */
    private fun applyChar(type: Int, value: Int, toggle: Boolean) {
        val et = editText ?: return
        val e = et.text
        val a = et.selectionStart.coerceAtLeast(0)
        val b = et.selectionEnd.coerceAtLeast(a)
        val same = e.getSpans(a, b, Any::class.java).filter { RichTextCodec.typeOf(it) == type }
        val covered = if (a == b) same.any { e.getSpanStart(it) <= a && a <= e.getSpanEnd(it) }
        else (a until b).all { i -> same.any { e.getSpanStart(it) <= i && i < e.getSpanEnd(it) } }
        same.forEach { s ->
            val ss = e.getSpanStart(s); val se = e.getSpanEnd(s); val v = RichTextCodec.valueOf(s)
            e.removeSpan(s)
            if (ss < a) e.setSpan(RichTextCodec.make(type, v), ss, a, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (se > b) e.setSpan(RichTextCodec.make(type, v), b, se, Spanned.SPAN_EXCLUSIVE_INCLUSIVE)
        }
        if (!(toggle && covered)) e.setSpan(RichTextCodec.make(type, value), a, b, RichTextCodec.flagsFor(type, a, b))
    }

    fun setAlign(align: Layout.Alignment) = applyParagraph(RichTextCodec.ALIGN, align.ordinal, toggle = false)
    fun toggleBullet() = applyParagraph(RichTextCodec.BULLET, 0, toggle = true)

    private fun applyParagraph(type: Int, value: Int, toggle: Boolean) {
        val et = editText ?: return
        val e: Editable = et.text
        val a = et.selectionStart.coerceAtLeast(0)
        val b = et.selectionEnd.coerceAtLeast(a)
        val pa = if (a == 0) 0 else e.lastIndexOf('\n', a - 1) + 1
        val pb = e.indexOf('\n', b).let { if (it < 0) e.length else it }
        val same = e.getSpans(pa, pb, Any::class.java).filter { RichTextCodec.typeOf(it) == type }
        same.forEach { e.removeSpan(it) }
        if (!(toggle && same.isNotEmpty())) e.setSpan(RichTextCodec.make(type, value), pa, pb, Spannable.SPAN_INCLUSIVE_INCLUSIVE)
    }
}

/** Platform EditText (IME, Hangul composition, selection handles) bound to [ctrl]. */
@Composable
fun RichTextEditor(ctrl: RichTextEditController, initial: CharSequence, widthPx: Int, modifier: Modifier = Modifier) {
    val widthDp = with(LocalDensity.current) { widthPx.toDp() }
    AndroidView(
        factory = { ctx ->
            EditText(ctx).apply {
                background = null
                setPadding(0, 0, 0, 0)
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, TEXT_BASE_PX)
                setTextColor(android.graphics.Color.BLACK)
                setText(SpannableStringBuilder(initial))
                setSelection(text.length)
                ctrl.editText = this
                post {
                    requestFocus()
                    context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
                }
            }
        },
        onRelease = {
            it.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0)
            if (ctrl.editText === it) ctrl.editText = null
        },
        modifier = modifier.width(widthDp),
    )
}

@Composable
fun TextFormatBar(ctrl: RichTextEditController, color: Color, onDone: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolChip("굵게", false) { ctrl.toggle(RichTextCodec.BOLD) }
        ToolChip("기울임", false) { ctrl.toggle(RichTextCodec.ITALIC) }
        ToolChip("밑줄", false) { ctrl.toggle(RichTextCodec.UNDERLINE) }
        ToolChip("작게", false) { ctrl.setSize(28) }
        ToolChip("보통", false) { ctrl.setSize(TEXT_BASE_PX.toInt()) }
        ToolChip("크게", false) { ctrl.setSize(64) }
        ToolChip("펜 색 적용", false) { ctrl.setColor(color.copy(alpha = 1f)) }
        ToolChip("왼쪽", false) { ctrl.setAlign(Layout.Alignment.ALIGN_NORMAL) }
        ToolChip("가운데", false) { ctrl.setAlign(Layout.Alignment.ALIGN_CENTER) }
        ToolChip("오른쪽", false) { ctrl.setAlign(Layout.Alignment.ALIGN_OPPOSITE) }
        ToolChip("글머리", false) { ctrl.toggleBullet() }
        ToolChip("완료", true, onDone)
        ToolChip("삭제", false, onDelete)
    }
}

// --- Compose text layer (Canvas / Ink: neither engine has text, so text is its own layer) -

class TextBox(val id: Long, x: Float, y: Float, val width: Int, text: CharSequence) {
    var x by mutableStateOf(x)
    var y by mutableStateOf(y)
    var text: CharSequence by mutableStateOf(text)
    private var cachedFor: CharSequence? = null
    private var cached: StaticLayout? = null
    val layout: StaticLayout
        get() {
            if (cachedFor !== text) { cached = richLayout(text, width); cachedFor = text }
            return cached!!
        }

    fun contains(px: Float, py: Float) = px in x..(x + width) && py in y..(y + layout.height.coerceAtLeast(TEXT_BASE_PX.toInt()))
}

class TextLayerState {
    val boxes = mutableStateListOf<TextBox>()
    var editing: TextBox? by mutableStateOf(null)
        private set
    private var nextId = 1L

    fun edit(box: TextBox) { editing = box }
    fun create(x: Float, y: Float) { TextBox(nextId++, x, y, TEXT_BOX_WIDTH, "").also { boxes += it; editing = it } }

    fun commit(ctrl: RichTextEditController) {
        val box = editing ?: return
        val text = ctrl.snapshot() ?: box.text
        if (text.isBlank()) boxes.remove(box) else box.text = text
        editing = null
    }

    fun deleteEditing() { editing?.let { boxes.remove(it) }; editing = null }
    fun clear() { boxes.clear(); editing = null }
}

/**
 * Text boxes drawn as their own vector layer above the strokes (never baked into the stroke
 * bitmap, so text stays sharp under zoom). Active = text mode: tap empty space to add a box,
 * tap a box to edit, drag a box to move it. A second finger is left to the parent zoom.
 */
@Composable
fun TextLayer(state: TextLayerState, active: Boolean, ctrl: RichTextEditController, modifier: Modifier = Modifier) {
    LaunchedEffect(active) { if (!active) state.commit(ctrl) }
    Box(modifier) {
        val input = if (!active) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val hit = state.boxes.lastOrNull { it.contains(down.position.x, down.position.y) && it !== state.editing }
                var last = down.position
                var dragged = false
                while (true) {
                    val e = awaitPointerEvent()
                    if (e.changes.count { it.pressed } >= 2) return@awaitEachGesture
                    val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    if (hit != null && (dragged || hypot(ch.position.x - down.position.x, ch.position.y - down.position.y) > 12f)) {
                        dragged = true
                        hit.x += ch.position.x - last.x; hit.y += ch.position.y - last.y
                        ch.consume()
                    }
                    last = ch.position
                }
                if (dragged) return@awaitEachGesture
                state.commit(ctrl)
                if (hit != null) state.edit(hit) else state.create(down.position.x, down.position.y)
            }
        }
        Canvas(Modifier.fillMaxSize().then(input)) {
            state.boxes.forEach { b ->
                if (b === state.editing) return@forEach
                val layout = b.layout
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save(); nc.translate(b.x, b.y); layout.draw(nc); nc.restore()
                }
            }
        }
        state.editing?.let { b ->
            key(b.id) { RichTextEditor(ctrl, b.text, b.width, Modifier.offset { IntOffset(b.x.roundToInt(), b.y.roundToInt()) }) }
        }
    }
}
