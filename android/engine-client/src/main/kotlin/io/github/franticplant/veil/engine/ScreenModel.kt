package io.github.franticplant.veil.engine

import io.github.franticplant.veil.engine.proto.Cursor
import io.github.franticplant.veil.engine.proto.Frame
import io.github.franticplant.veil.engine.proto.Modes
import io.github.franticplant.veil.engine.proto.Row
import io.github.franticplant.veil.engine.proto.RunFlag

/** A frame the engine sent breaks the protocol's rules. */
class InvalidFrameException(message: String) : Exception(message)

/**
 * One session's screen, as the engine last described it.
 *
 * Frames are validated before they touch the model: sizes and indices within bounds, runs inside
 * their row and in order, text consistent with the cells it claims. This is the frontend's half of
 * the render boundary (the design's RenderGate): once the engine runs in a VM, a frame is untrusted
 * input, and a bad one must end the session, not reach the renderer.
 *
 * Not thread-safe: the owner applies frames and reads the model from one thread, or guards it.
 */
class ScreenModel {
    var cols = 0
        private set

    var rows = 0
        private set

    /** Row contents by index; null until the engine sent that row. */
    var lines: Array<Row?> = emptyArray()
        private set

    var cursor: Cursor = Cursor.getDefaultInstance()
        private set

    var modes: Modes = Modes.getDefaultInstance()
        private set

    var title = ""
        private set

    var scrollOffset = 0
        private set

    var historyLines = 0
        private set

    /** seq of the last frame applied. */
    var seq = 0L
        private set

    /** Validates [frame] and applies it. Throws, leaving the model unchanged, if it is invalid. */
    fun apply(frame: Frame) {
        validate(frame)
        val c = frame.size.cols
        val r = frame.size.rows
        if (frame.full || c != cols || r != rows) {
            cols = c
            rows = r
            lines = arrayOfNulls(r)
        }
        for (row in frame.rowsList) lines[row.index] = row
        cursor = frame.cursor
        modes = frame.modes
        title = frame.title
        scrollOffset = frame.scrollOffset
        historyLines = frame.historyLines
        seq = frame.seq
    }

    /**
     * The screen as plain text, one string per row, trailing blanks trimmed. A wide character
     * occupies its first cell; its second cell adds nothing.
     */
    fun text(): List<String> = lines.map { row ->
        if (row == null) return@map ""
        val cells = arrayOfNulls<String>(cols)
        for (run in row.runsList) {
            val wide = run.flags and RunFlag.RUN_FLAG_WIDE_VALUE != 0
            val perCell = if (wide) 2 else 1
            val scalars = run.text.codePointCount(0, run.text.length)
            if (scalars * perCell == run.cells) {
                var i = 0
                var col = run.col
                while (i < run.text.length) {
                    val cp = run.text.codePointAt(i)
                    cells[col] = String(Character.toChars(cp))
                    if (wide && col + 1 < cols) cells[col + 1] = ""
                    i += Character.charCount(cp)
                    col += perCell
                }
            } else {
                // One grapheme cluster.
                cells[run.col] = run.text
                if (wide && run.col + 1 < cols) cells[run.col + 1] = ""
            }
        }
        buildString { for (c in cells) append(c ?: " ") }.trimEnd()
    }

    private fun validate(frame: Frame) {
        fun bad(msg: String): Nothing = throw InvalidFrameException(msg)

        if (frame.seq <= seq) bad("frame seq ${frame.seq} after ${seq}")
        if (!frame.hasSize()) bad("frame without size")
        val c = frame.size.cols
        val r = frame.size.rows
        if (c !in MIN_COLS..MAX_COLS || r !in MIN_ROWS..MAX_ROWS) bad("size ${c}x$r out of bounds")
        if (!frame.full && (c != cols || r != rows)) bad("size changed in a partial frame")
        if (frame.rowsCount > r) bad("${frame.rowsCount} rows for a $r-row screen")
        val titleChars = frame.title.codePointCount(0, frame.title.length)
        if (titleChars > MAX_TITLE_CHARS || frame.title.any { it.isISOControl() }) {
            bad("bad title")
        }
        val cur = frame.cursor
        if (cur.row >= r || cur.col >= c) bad("cursor ${cur.row},${cur.col} outside the screen")

        for (row in frame.rowsList) {
            if (row.index !in 0 until r) bad("row index ${row.index}")
            var next = 0
            for (run in row.runsList) {
                val wide = run.flags and RunFlag.RUN_FLAG_WIDE_VALUE != 0
                if (run.col < next) bad("runs overlap or are out of order in row ${row.index}")
                if (run.cells <= 0 || run.col + run.cells > c) bad("run outside row ${row.index}")
                val scalars = run.text.codePointCount(0, run.text.length)
                val perCell = if (wide) 2 else 1
                val oneCluster = run.cells == perCell && scalars >= 1
                if (scalars * perCell != run.cells && !oneCluster) {
                    bad("run text does not match its ${run.cells} cells in row ${row.index}")
                }
                if (run.text.any { it.isISOControl() }) bad("control character in row ${row.index}")
                next = run.col + run.cells
            }
        }
    }

    companion object {
        // Mirror core/protocol's bounds.
        const val MIN_COLS = 2
        const val MAX_COLS = 500
        const val MIN_ROWS = 2
        const val MAX_ROWS = 300
        const val MAX_TITLE_CHARS = 256
    }
}
