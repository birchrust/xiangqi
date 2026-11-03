package dev.xiangqi.assistant.domain.tracking

import dev.xiangqi.assistant.domain.board.BoardMove
import dev.xiangqi.assistant.domain.board.BoardPosition
import org.junit.Assert.*
import org.junit.Test

class BoardTrackerTest {
    private val start = BoardPosition(
        "rnbakabnr" + "........." + ".c.....c." + "p.p.p.p.p" + "........." +
            "........." + "P.P.P.P.P" + ".C.....C." + "........." + "RNBAKABNR", true)

    private fun confirm(tracker: BoardTracker, position: BoardPosition): TrackedBoard {
        assertNull(tracker.accept(position.cells))
        return assertNotNullResult(tracker.accept(position.cells))
    }

    private fun assertNotNullResult(result: TrackedBoard?): TrackedBoard {
        assertNotNull(result)
        return result!!
    }

    @Test fun redMoveSwitchesToBlackAndBlackReplySwitchesToRed() {
        val tracker = BoardTracker(true)
        confirm(tracker, start)
        val afterRed = start.play(BoardMove(70, 67))
        val black = confirm(tracker, afterRed)
        assertEquals(afterRed, black.position)
        assertFalse(black.position.redToMove)
        assertEquals(ObservedMove(start, BoardMove(70, 67)), black.observation.lastMove)
        val afterBlack = afterRed.play(BoardMove(7, 24))
        assertEquals(afterBlack, confirm(tracker, afterBlack).position)
        assertTrue(tracker.accept(afterBlack.cells)!!.position.redToMove)
    }

    @Test fun checkingMoveIsValidatedWithTheNewTurn() {
        val cells = CharArray(90) { '.' }.apply { this[4] = 'k'; this[85] = 'K'; this[49] = 'P'; this[9] = 'R' }
        val before = BoardPosition(String(cells), true)
        val tracker = BoardTracker(true)
        confirm(tracker, before)
        val after = before.play(BoardMove(9, 13))
        assertThrows(IllegalArgumentException::class.java) { after.copy(redToMove = true).validate() }
        assertEquals(after, confirm(tracker, after).position)
        assertFalse(tracker.accept(after.cells)!!.position.redToMove)
    }

    @Test fun firstMoveCanCorrectTheManualSeed() {
        val tracker = BoardTracker(false)
        assertFalse(confirm(tracker, start).position.redToMove)
        assertFalse(confirm(tracker, start.play(BoardMove(70, 67))).position.redToMove)
    }

    @Test fun missesAndAnimationRollbackDoNotChangeTheConfirmedTurn() {
        val tracker = BoardTracker(true)
        confirm(tracker, start)
        val afterRed = start.play(BoardMove(70, 67))
        confirm(tracker, afterRed)
        tracker.miss()
        // Two old frames could otherwise look like the same cannon moving back.
        assertNull(tracker.accept(start.cells))
        assertNull(tracker.accept(start.cells))
        assertFalse(tracker.accept(afterRed.cells)!!.position.redToMove)
        assertTrue(confirm(tracker, afterRed.play(BoardMove(7, 24))).position.redToMove)
    }

    @Test fun fastAlternatingReplyCanSkipOneSample() {
        val tracker = BoardTracker(true)
        confirm(tracker, start)
        val afterRed = start.play(BoardMove(70, 67))
        confirm(tracker, afterRed)
        val afterTwoMore = afterRed.play(BoardMove(7, 24)).play(BoardMove(82, 65))
        assertEquals(afterTwoMore, confirm(tracker, afterTwoMore).position)
        assertFalse(tracker.accept(afterTwoMore.cells)!!.position.redToMove)
    }

    @Test fun occlusionCannotReplaceTheBaselineOrChangeTheTurn() {
        val tracker = BoardTracker(true)
        confirm(tracker, start)
        val missingCannon = start.cells.replaceRange(64, 65, ".")
        repeat(3) { assertNull(tracker.accept(missingCannon)) }
        assertFalse(confirm(tracker, start.play(BoardMove(70, 67))).position.redToMove)
    }

    @Test fun unchangedFramesReuseTheConfirmedResultAndDiscardPendingNoise() {
        val tracker = BoardTracker(true)
        val accepted = confirm(tracker, start)
        repeat(10) { assertSame(accepted, tracker.accept(start.cells)) }
        val afterRed = start.play(BoardMove(70, 67))
        assertNull(tracker.accept(afterRed.cells))
        assertSame(accepted, tracker.accept(start.cells))
        assertNull(tracker.accept(afterRed.cells))
        assertEquals(afterRed, tracker.accept(afterRed.cells)!!.position)
    }
}
