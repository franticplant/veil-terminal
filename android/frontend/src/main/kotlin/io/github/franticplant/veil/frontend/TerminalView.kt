package io.github.franticplant.veil.frontend

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.InputType
import android.util.TypedValue
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import io.github.franticplant.veil.engine.ScreenModel
import io.github.franticplant.veil.engine.Session
import io.github.franticplant.veil.engine.proto.CursorShape
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
        canvas.drawColor(Palette.DEFAULT_BG)
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
                var fg = Palette.resolve(run.fg, Palette.DEFAULT_FG, bold)
                var bg = Palette.resolve(run.bg, Palette.DEFAULT_BG)
                if (f and RunFlag.RUN_FLAG_INVERSE_VALUE != 0) fg = bg.also { bg = fg }
                val x = run.col * cellW
                if (bg != Palette.DEFAULT_BG) {
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
        bgPaint.color = Palette.CURSOR
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

    // ---- Touch ------------------------------------------------------------

    private val gestures =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    showKeyboard()
                    return true
                }

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    dx: Float,
                    dy: Float,
                ): Boolean {
                    scrollRemainder += dy
                    val lines = (scrollRemainder / cellH).toInt()
                    if (lines != 0) {
                        scrollRemainder -= lines * cellH
                        // Dragging down (dy < 0) reveals older lines.
                        session?.scroll(-lines)
                    }
                    return true
                }
            },
        )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) scrollRemainder = 0f
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java).showSoftInput(this, 0)
    }

    // ---- Keyboard ---------------------------------------------------------

    /** Sends [bytes] to the session. */
    fun send(bytes: ByteArray) {
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
}
