package dev.xiangqi.assistant.presentation

import dev.xiangqi.assistant.engine.model.UciScore
import org.junit.Assert.*
import org.junit.Test

class AnalysisFormattingTest {
    @Test fun scoresAlwaysUseRedsPerspective() {
        assertEquals("+1.25", UciScore("cp", 125).redText(true))
        assertEquals("-1.25", UciScore("cp", 125).redText(false))
        assertEquals("+0.40", UciScore("cp", -40).redText(false))
        assertEquals("−M3", UciScore("mate", 3).redText(false))
        assertEquals("+M3", UciScore("mate", -3).redText(false))
    }
}
