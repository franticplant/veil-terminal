package io.github.franticplant.veil.frontend

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.franticplant.veil.engine.ScreenModel
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.proto.CursorShape
import io.github.franticplant.veil.engine.proto.MouseMode
import io.github.franticplant.veil.engine.proto.RunFlag
import io.github.franticplant.veil.frontend.KeyEncoder.Key
import io.github.franticplant.veil.frontend.KeyEncoder.Mods
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws a session's [ScreenModel] and turns keyboard input into bytes for it.
 *
 * It renders only what the engine's frames describe, plain cells with colors and attributes, and
 * never interprets terminal control data. Input goes out through [KeyEncoder].
 */
class TerminalView(context: Context) : View(context) {
    /** The session shown; null shows nothing. */
    var session: Session? = null
        set(value) {
            if (field === value) return
            field = value
            value?.requestSnapshot()
            lastReportedSize = null
            reportSize()
            invalidate()
        }

    /** Called with the grid size that fits the view, whenever it changes. */
    var onGridSize: ((cols: Int, rows: Int) -> Unit)? = null

    /** Sticky modifiers from the extra-keys row; consumed by the next key. */
    var stickyCtrl = false
    var stickyAlt = false
    var onStickyConsumed: (() -> Unit)? = null

    /** The color theme. */
    var palette: Palette = Palette.VEIL
        set(value) {
            if (field === value) return
            field = value
            invalidate()
        }

    var fontSizeSp = 13f
        set(value) {
            field = value.coerceIn(7f, 32f)
            updateMetrics()
            lastReportedSize = null
            reportSize()
            invalidate()
        }

    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            isSubpixelText = true
        }
    private val bgPaint = Paint()
    private var cellW = 1f
    private var cellH = 1f
    private var baseline = 0f
    private var lastReportedSize: Pair<Int, Int>? = null
    private var scrollRemainder = 0f

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        updateMetrics()
    }

    private fun updateMetrics() {
        paint.textSize =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                fontSizeSp,
                resources.displayMetrics,
            )
        cellW = paint.measureText("M")
        val fm = paint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent)
        baseline = -fm.ascent
    }

    /** The grid that fits [w] x [h] pixels, within the protocol's bounds. */
    fun gridFor(w: Int, h: Int): Pair<Int, Int> =
        Pair(
            (w / cellW).toInt().coerceIn(ScreenModel.MIN_COLS, ScreenModel.MAX_COLS),
            (h / cellH).toInt().coerceIn(ScreenModel.MIN_ROWS, ScreenModel.MAX_ROWS),
        )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportSize()
    }

    private fun reportSize() {
        if (width == 0 || height == 0) return
        val size = gridFor(width, height)
        if (size == lastReportedSize) return
        lastReportedSize = size
        onGridSize?.invoke(size.first, size.second)
    }

    // ---- Drawing ----------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(palette.bg)
        val s = session ?: return
        synchronized(s.screen) { drawScreen(canvas, s.screen) }
    }

    private fun drawScreen(canvas: Canvas, screen: ScreenModel) {
        for ((r, row) in screen.lines.withIndex()) {
            row ?: continue
            val y = r * cellH
            for (run in row.runsList) {
                val f = run.flags
                val bold = f and RunFlag.RUN_FLAG_BOLD_VALUE != 0
                var fg = palette.resolve(run.fg, palette.fg, bold)
                var bg = palette.resolve(run.bg, palette.bg)
                if (f and RunFlag.RUN_FLAG_INVERSE_VALUE != 0) fg = bg.also { bg = fg }
                val x = run.col * cellW
                if (bg != palette.bg) {
                    bgPaint.color = bg
                    canvas.drawRect(x, y, x + run.cells * cellW, y + cellH, bgPaint)
                }
                if (f and RunFlag.RUN_FLAG_HIDDEN_VALUE != 0) continue
                paint.color = fg
                if (f and RunFlag.RUN_FLAG_DIM_VALUE != 0) paint.alpha = 150
                paint.isFakeBoldText = bold
                paint.textSkewX = if (f and RunFlag.RUN_FLAG_ITALIC_VALUE != 0) -0.2f else 0f
                paint.isUnderlineText = f and RunFlag.RUN_FLAG_UNDERLINE_VALUE != 0
                paint.isStrikeThruText = f and RunFlag.RUN_FLAG_STRIKEOUT_VALUE != 0
                drawRunText(
                    canvas,
                    run.text,
                    run.col,
                    run.cells,
                    f and RunFlag.RUN_FLAG_WIDE_VALUE != 0,
                    y,
                )
            }
        }
        drawSelection(canvas)
        drawCursor(canvas, screen)
    }

    /** Each character at its own cell, so fallback glyphs of other widths cannot drift. */
    private fun drawRunText(
        canvas: Canvas,
        text: String,
        col: Int,
        cells: Int,
        wide: Boolean,
        y: Float,
    ) {
        val per = if (wide) 2 else 1
        val scalars = text.codePointCount(0, text.length)
        if (scalars * per != cells) {
            // One cluster (base + combining marks) in one cell.
            canvas.drawText(text, col * cellW, y + baseline, paint)
            return
        }
        var i = 0
        var c = col
        while (i < text.length) {
            val n = Character.charCount(text.codePointAt(i))
            canvas.drawText(text, i, i + n, c * cellW, y + baseline, paint)
            i += n
            c += per
        }
    }

    private fun drawCursor(canvas: Canvas, screen: ScreenModel) {
        val cur = screen.cursor
        if (!cur.visible || screen.scrollOffset != 0) return
        val x = cur.col * cellW
        val y = cur.row * cellH
        bgPaint.color = palette.cursor
        when (cur.shape) {
            CursorShape.CURSOR_SHAPE_BEAM ->
                canvas.drawRect(x, y, x + max(2f, cellW / 8), y + cellH, bgPaint)
            CursorShape.CURSOR_SHAPE_UNDERLINE ->
                canvas.drawRect(x, y + cellH - max(2f, cellH / 10), x + cellW, y + cellH, bgPaint)
            else -> {
                bgPaint.alpha = 160
                canvas.drawRect(x, y, x + cellW, y + cellH, bgPaint)
                bgPaint.alpha = 255
            }
        }
    }

    // ---- Touch and mouse ----------------------------------------------------
    //
    // Fingers: a tap is a left click when the remote reports the mouse, otherwise it opens the
    // keyboard (a long press always does). Dragging scrolls: wheel events when the remote reports
    // the mouse, arrow keys on the alternate screen (less, vim), otherwise scrollback. Two fingers
    // pinch the text size, which changes the grid.
    //
    // A mouse: buttons, drags and hover are reported as the remote asked; the wheel scrolls as
    // above; a right click pastes when the remote does not want the mouse.

    /** Called when pinching changed the text size, to remember it. */
    var onFontSizeChanged: ((Float) -> Unit)? = null

    /** Called for a paste request from the view (right click, Ctrl+Shift+V). */
    var onPasteRequest: (() -> Unit)? = null

    private var scaling = false
    private var mouseButton: MouseEncoder.Button? = null
    private var lastMouseCell: Pair<Int, Int>? = null

    private fun modes() = session?.let { synchronized(it.screen) { it.screen.modes } }

    private fun mouseMode() = modes()?.mouse ?: MouseMode.MOUSE_MODE_NONE

    private fun cellAt(x: Float, y: Float): Pair<Int, Int> {
        val s = session
        val (cols, rows) =
            if (s != null) synchronized(s.screen) { s.screen.cols to s.screen.rows } else 1 to 1
        return (x / cellW).toInt().coerceIn(0, max(0, cols - 1)) to
            (y / cellH).toInt().coerceIn(0, max(0, rows - 1))
    }

    private fun report(
        button: MouseEncoder.Button,
        kind: MouseEncoder.Kind,
        x: Float,
        y: Float,
        mods: Mods = Mods(),
    ) {
        val (c, r) = cellAt(x, y)
        val sgr = modes()?.mouseSgr ?: false
        MouseEncoder.encode(button, kind, c, r, sgr, mods)?.let(::send)
    }

    /** Scrolls [lines] (positive: towards older content) at [x], [y]. */
    private fun scrollLines(lines: Int, x: Float, y: Float) {
        if (lines == 0) return
        val n = lines.coerceIn(-50, 50)
        val m = modes()
        when {
            m != null && m.mouse != MouseMode.MOUSE_MODE_NONE -> {
                val b = if (n > 0) MouseEncoder.Button.WHEEL_UP else MouseEncoder.Button.WHEEL_DOWN
                repeat(kotlin.math.abs(n)) { report(b, MouseEncoder.Kind.PRESS, x, y) }
            }
            m != null && m.altScreen -> {
                val key = if (n > 0) Key.UP else Key.DOWN
                repeat(kotlin.math.abs(n)) { send(KeyEncoder.key(key, Mods(), m.appCursor)) }
            }
            else -> session?.scroll(n)
        }
    }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private var start = 0f

                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    scaling = true
                    start = fontSizeSp
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val target = (fontSizeSp * detector.scaleFactor).coerceIn(7f, 32f)
                    // Half-point steps, so the grid does not churn on every frame of a pinch.
                    if (kotlin.math.abs(target - fontSizeSp) >= 0.5f) {
                        fontSizeSp = Math.round(target * 2) / 2f
                        return true
                    }
                    return false
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    if (fontSizeSp != start) onFontSizeChanged?.invoke(fontSizeSp)
                }
            },
        )

    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    if (scaling) return true
                    if (mouseMode() != MouseMode.MOUSE_MODE_NONE) {
                        val (c, r) = cellAt(e.x, e.y)
                        MouseEncoder.click(
                                MouseEncoder.Button.LEFT,
                                c,
                                r,
                                modes()?.mouseSgr ?: false,
                            )
                            ?.let(::send)
                    } else {
                        showKeyboard()
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (scaling) return
                    // Long press selects the word under the finger; dragging extends it.
                    val cell = cellAt(e.x, e.y)
                    val (from, to) = wordAt(cell)
                    selAnchor = from
                    selStart = from
                    selEnd = to
                    selecting = true
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onSelectionChanged?.invoke(true)
                    invalidate()
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    if (scaling || e2.pointerCount > 1) return true
                    scrollRemainder += dy
                    val lines = (scrollRemainder / cellH).toInt()
                    if (lines != 0) {
                        scrollRemainder -= lines * cellH
                        // Dragging down (dy < 0) reveals older lines.
                        scrollLines(-lines, e2.x, e2.y)
                    }
                    return true
                }
            },
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return onMouse(event)
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val cell = cellAt(event.x, event.y)
                    val a = selAnchor ?: cell
                    if (before(cell, a)) {
                        selStart = cell
                        selEnd = a
                    } else {
                        selStart = a
                        selEnd = cell
                    }
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    return true
                }
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            scrollRemainder = 0f
            scaling = false
        }
        scaleDetector.onTouchEvent(event)
        val handled = gestures.onTouchEvent(event)
        if (
            event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
        ) {
            scaling = false
        }
        return handled || super.onTouchEvent(event)
    }

    private fun buttonOf(state: Int): MouseEncoder.Button? =
        when {
            state and MotionEvent.BUTTON_PRIMARY != 0 -> MouseEncoder.Button.LEFT
            state and MotionEvent.BUTTON_TERTIARY != 0 -> MouseEncoder.Button.MIDDLE
            state and MotionEvent.BUTTON_SECONDARY != 0 -> MouseEncoder.Button.RIGHT
            else -> null
        }

    private fun mouseMods(e: MotionEvent) =
        Mods(
            ctrl = e.metaState and KeyEvent.META_CTRL_ON != 0,
            alt = e.metaState and KeyEvent.META_ALT_ON != 0,
            shift = e.metaState and KeyEvent.META_SHIFT_ON != 0,
        )

    /** A mouse's buttons and drags. */
    private fun onMouse(e: MotionEvent): Boolean {
        val mode = mouseMode()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                val b = buttonOf(e.buttonState) ?: MouseEncoder.Button.LEFT
                if (mode == MouseMode.MOUSE_MODE_NONE) {
                    if (b != MouseEncoder.Button.LEFT) onPasteRequest?.invoke()
                    return true
                }
                mouseButton = b
                lastMouseCell = cellAt(e.x, e.y)
                report(b, MouseEncoder.Kind.PRESS, e.x, e.y, mouseMods(e))
            }
            MotionEvent.ACTION_MOVE -> {
                val b = mouseButton ?: return true
                if (mode != MouseMode.MOUSE_MODE_DRAG && mode != MouseMode.MOUSE_MODE_MOTION)
                    return true
                val cell = cellAt(e.x, e.y)
                if (cell == lastMouseCell) return true
                lastMouseCell = cell
                report(b, MouseEncoder.Kind.MOTION, e.x, e.y, mouseMods(e))
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val b = mouseButton ?: return true
                mouseButton = null
                if (mode != MouseMode.MOUSE_MODE_NONE)
                    report(b, MouseEncoder.Kind.RELEASE, e.x, e.y, mouseMods(e))
            }
        }
        return true
    }

    /** The wheel, and hover motion when the remote asked for all motion. */
    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return super.onGenericMotionEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                // One notch is three lines, as on most desktops.
                val lines = Math.round(v * 3)
                scrollLines(lines, e.x, e.y)
                return true
            }
            MotionEvent.ACTION_HOVER_MOVE -> {
                if (mouseMode() != MouseMode.MOUSE_MODE_MOTION) return false
                val cell = cellAt(e.x, e.y)
                if (cell == lastMouseCell) return true
                lastMouseCell = cell
                report(MouseEncoder.Button.NONE, MouseEncoder.Kind.MOTION, e.x, e.y, mouseMods(e))
                return true
            }
        }
        return super.onGenericMotionEvent(e)
    }

    // ---- Selection ----------------------------------------------------------

    /** Called when a selection appears (true) or goes away (false). */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private var selecting = false
    private var selAnchor: Pair<Int, Int>? = null
    private var selStart: Pair<Int, Int>? = null
    private var selEnd: Pair<Int, Int>? = null

    val hasSelection: Boolean
        get() = selStart != null

    private fun before(a: Pair<Int, Int>, b: Pair<Int, Int>) =
        a.second < b.second || (a.second == b.second && a.first < b.first)

    private fun cellsNow(): List<Array<String?>> =
        session?.let { synchronized(it.screen) { it.screen.cells() } } ?: emptyList()

    /**
     * The word around [cell] (col, row): runs of non-blank characters, stopping at brackets and
     * quotes.
     */
    private fun wordAt(cell: Pair<Int, Int>): Pair<Pair<Int, Int>, Pair<Int, Int>> {
        val row = cellsNow().getOrNull(cell.second) ?: return cell to cell
        fun isWord(c: String?) = c != null && c.isNotBlank() && c !in WORD_STOPS
        if (!isWord(row.getOrNull(cell.first))) return cell to cell
        var a = cell.first
        var b = cell.first
        while (a > 0 && (isWord(row[a - 1]) || row[a - 1] == "")) a--
        while (b < row.size - 1 && (isWord(row[b + 1]) || row[b + 1] == "")) b++
        return (a to cell.second) to (b to cell.second)
    }

    /** The selected text, lines joined with newlines, trailing blanks trimmed; null if none. */
    fun selectedText(): String? {
        val s = selStart ?: return null
        val e = selEnd ?: return null
        val cells = cellsNow()
        val out = StringBuilder()
        for (r in s.second..e.second) {
            val row = cells.getOrNull(r) ?: continue
            val from = if (r == s.second) s.first else 0
            val to = if (r == e.second) e.first else row.size - 1
            val line = buildString {
                for (c in from..minOf(to, row.size - 1)) append(row[c] ?: " ")
            }
            out.append(line.trimEnd())
            if (r != e.second) out.append('\n')
        }
        return out.toString()
    }

    fun selectAll() {
        val cells = cellsNow()
        if (cells.isEmpty()) return
        selStart = 0 to 0
        selEnd = (cells[0].size - 1).coerceAtLeast(0) to cells.size - 1
        onSelectionChanged?.invoke(true)
        invalidate()
    }

    fun clearSelection() {
        if (selStart == null) return
        selStart = null
        selEnd = null
        selAnchor = null
        selecting = false
        onSelectionChanged?.invoke(false)
        invalidate()
    }

    private fun drawSelection(canvas: Canvas) {
        val s = selStart ?: return
        val e = selEnd ?: return
        val cols = (width / cellW).toInt()
        bgPaint.color = palette.cursor
        bgPaint.alpha = 90
        for (r in s.second..e.second) {
            val from = if (r == s.second) s.first else 0
            val to = if (r == e.second) e.first else cols - 1
            canvas.drawRect(from * cellW, r * cellH, (to + 1) * cellW, (r + 1) * cellH, bgPaint)
        }
        bgPaint.alpha = 255
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    // ---- Keyboard ---------------------------------------------------------

    /** Sends [bytes] to the session. */
    fun send(bytes: ByteArray) {
        clearSelection()
        session?.input(bytes)
    }

    private fun takeMods(
        shift: Boolean = false,
        ctrl: Boolean = false,
        alt: Boolean = false,
    ): Mods {
        val mods = Mods(ctrl = ctrl || stickyCtrl, alt = alt || stickyAlt, shift = shift)
        if (stickyCtrl || stickyAlt) {
            stickyCtrl = false
            stickyAlt = false
            onStickyConsumed?.invoke()
        }
        return mods
    }

    fun sendKey(key: Key) {
        val appCursor =
            session?.let { synchronized(it.screen) { it.screen.modes.appCursor } } ?: false
        send(KeyEncoder.key(key, takeMods(), appCursor))
    }

    fun sendText(text: String) {
        send(KeyEncoder.text(text, takeMods()))
    }

    fun paste(text: String) {
        val bracketed =
            session?.let { synchronized(it.screen) { it.screen.modes.bracketedPaste } } ?: false
        send(KeyEncoder.paste(text, bracketed))
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain characters without prediction, so keys arrive as they are typed.
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions =
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_ACTION_NONE
        return TerminalInputConnection()
    }

    private inner class TerminalInputConnection : BaseInputConnection(this@TerminalView, false) {
        private var composing = ""

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = ""
            if (text.isNotEmpty()) sendText(text.toString())
            return true
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            composing = text.toString()
            return true
        }

        override fun finishComposingText(): Boolean {
            if (composing.isNotEmpty()) sendText(composing)
            composing = ""
            return true
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceIn(0, 64)) { send(KeyEncoder.key(Key.BACKSPACE)) }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
            return true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val key =
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> Key.ENTER
                KeyEvent.KEYCODE_TAB -> Key.TAB
                KeyEvent.KEYCODE_DEL -> Key.BACKSPACE
                KeyEvent.KEYCODE_FORWARD_DEL -> Key.DELETE
                KeyEvent.KEYCODE_ESCAPE -> Key.ESCAPE
                KeyEvent.KEYCODE_DPAD_UP -> Key.UP
                KeyEvent.KEYCODE_DPAD_DOWN -> Key.DOWN
                KeyEvent.KEYCODE_DPAD_LEFT -> Key.LEFT
                KeyEvent.KEYCODE_DPAD_RIGHT -> Key.RIGHT
                KeyEvent.KEYCODE_MOVE_HOME -> Key.HOME
                KeyEvent.KEYCODE_MOVE_END -> Key.END
                KeyEvent.KEYCODE_PAGE_UP -> Key.PAGE_UP
                KeyEvent.KEYCODE_PAGE_DOWN -> Key.PAGE_DOWN
                KeyEvent.KEYCODE_INSERT -> Key.INSERT
                in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 ->
                    Key.entries[Key.F1.ordinal + keyCode - KeyEvent.KEYCODE_F1]
                else -> null
            }
        val appCursor =
            session?.let { synchronized(it.screen) { it.screen.modes.appCursor } } ?: false
        if (key != null) {
            send(
                KeyEncoder.key(
                    key,
                    takeMods(event.isShiftPressed, event.isCtrlPressed, event.isAltPressed),
                    appCursor,
                )
            )
            return true
        }
        // Ctrl+Shift+V pastes, as in desktop terminals.
        if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && event.isShiftPressed) {
            onPasteRequest?.invoke()
            return true
        }
        // Printable keys from a hardware keyboard.
        val ch =
            event.getUnicodeChar(
                event.metaState and KeyEvent.META_CTRL_MASK.inv() and KeyEvent.META_ALT_MASK.inv()
            )
        if (ch > 0) {
            val mods = takeMods(ctrl = event.isCtrlPressed, alt = event.isAltPressed)
            send(KeyEncoder.text(String(Character.toChars(ch)), mods))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private val WORD_STOPS =
            setOf("(", ")", "[", "]", "{", "}", "<", ">", "\"", "'", "`", "|", ",", ";", "=")
    }
}
