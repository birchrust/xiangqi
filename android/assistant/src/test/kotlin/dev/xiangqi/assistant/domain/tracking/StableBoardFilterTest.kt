package dev.xiangqi.assistant.domain.tracking

import org.junit.Assert.*
import org.junit.Test

class StableBoardFilterTest {
    @Test fun initialAndNewBoardsRequireTwoFrames() {
        val filter = StableBoardFilter()
        assertFalse(filter.accept("A"))
        assertTrue(filter.accept("A"))
        filter.confirm("A")
        assertFalse(filter.accept("B"))
        assertTrue(filter.accept("B"))
        filter.confirm("B")
        assertTrue(filter.accept("B"))
    }

    @Test fun occlusionDoesNotForgetTheConfirmedBoard() {
        val filter = StableBoardFilter()
        filter.confirm("A")
        filter.miss()
        assertTrue(filter.accept("A"))
        assertFalse(filter.accept("noise"))
        filter.miss()
        assertTrue(filter.accept("A"))
    }

    @Test fun rejectedTransitionsDoNotReplaceTheConfirmedBoard() {
        val filter = StableBoardFilter()
        filter.confirm("A")
        assertFalse(filter.accept("noise"))
        assertTrue(filter.accept("noise"))
        // The observer rejects this candidate, so confirm() is never called.
        assertTrue(filter.accept("A"))
    }
}
