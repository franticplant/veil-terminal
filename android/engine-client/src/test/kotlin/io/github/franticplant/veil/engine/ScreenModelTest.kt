package io.github.franticplant.veil.engine

import io.github.franticplant.veil.engine.proto.Cursor
import io.github.franticplant.veil.engine.proto.Frame
import io.github.franticplant.veil.engine.proto.Row
import io.github.franticplant.veil.engine.proto.Run
import io.github.franticplant.veil.engine.proto.RunFlag
import io.github.franticplant.veil.engine.proto.TermSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class ScreenModelTest {
    private fun run(col: Int, text: String, cells: Int = text.length, flags: Int = 0) =
        Run.newBuilder().setCol(col).setCells(cells).setText(text).setFlags(flags).build()

    private fun frame(seq: Long, full: Boolean = true, vararg rows: Row) =
        Frame.newBuilder()
            .setSeq(seq)
            .setFull(full)
            .setSize(TermSize.newBuilder().setCols(10).setRows(3))
            .setCursor(Cursor.newBuilder().setRow(0).setCol(0))
            .addAllRows(rows.toList())
            .build()

    private fun row(index: Int, vararg runs: Run) =
        Row.newBuilder().setIndex(index).addAllRuns(runs.toList()).build()

    private fun assertInvalid(model: ScreenModel, f: Frame) {
        val before = model.seq
        try {
            model.apply(f)
            fail("accepted an invalid frame")
        } catch (e: InvalidFrameException) {
            assertEquals("model unchanged", before, model.seq)
        }
    }

    @Test
    fun appliesFullThenPartialFrames() {
        val m = ScreenModel()
        m.apply(frame(1, true, row(0, run(0, "hello")), row(1, run(2, "x"))))
        assertEquals(10, m.cols)
        assertEquals("hello", m.lines[0]!!.getRuns(0).text)
        m.apply(frame(2, false, row(0, run(0, "bye"))))
        assertEquals("bye", m.lines[0]!!.getRuns(0).text)
        assertEquals("x", m.lines[1]!!.getRuns(0).text)
        assertNull(m.lines[2])
    }

    @Test
    fun acceptsWideRunsAndClusters() {
        val m = ScreenModel()
        val wide = RunFlag.RUN_FLAG_WIDE_VALUE
        m.apply(
            frame(
                1,
                true,
                row(0, run(0, "漢字", cells = 4, flags = wide), run(4, "é", cells = 1)),
            )
        )
        assertEquals(listOf("漢字e\u0301", "", ""), m.text())
    }

    @Test
    fun textPlacesRunsAtTheirColumns() {
        val m = ScreenModel()
        m.apply(frame(1, true, row(0, run(0, "$"), run(2, "ls")), row(2, run(7, "end"))))
        assertEquals(listOf("$ ls", "", "       end"), m.text())
    }

    @Test
    fun refusesFramesThatBreakTheContract() {
        val m = ScreenModel()
        m.apply(frame(5))
        assertInvalid(m, frame(5)) // seq must increase
        assertInvalid(m, frame(6, true, row(3, run(0, "a")))) // row index outside the screen
        assertInvalid(m, frame(6, true, row(0, run(8, "abc")))) // run past the last column
        assertInvalid(m, frame(6, true, row(0, run(0, "abc"), run(1, "d")))) // overlap
        assertInvalid(m, frame(6, true, row(0, run(0, "abc", cells = 5)))) // text vs cells
        assertInvalid(m, frame(6, true, row(0, run(0, "a\u001b[31m")))) // control characters
        assertInvalid(
            m,
            frame(6).toBuilder().setSize(TermSize.newBuilder().setCols(100_000).setRows(3)).build(),
        )
        assertInvalid(
            m,
            frame(6).toBuilder().setCursor(Cursor.newBuilder().setRow(9).setCol(0)).build(),
        )
        assertInvalid(m, frame(6).toBuilder().setTitle("bad\u0007title").build())
        assertInvalid(
            m,
            frame(6, false)
                .toBuilder()
                .setSize(TermSize.newBuilder().setCols(20).setRows(3))
                .build(),
        )
    }
}
