package com.hippo.ehviewer.ui

import com.ehviewer.core.ui.util.ScrollDirectionGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScrollDirectionGateTest {
    @Test
    fun smallScrollFramesAccumulateUntilAVisibleDirectionIsEstablished() {
        val gate = ScrollDirectionGate(32f, 24f)
        repeat(7) { assertNull(gate.update(4f)) }
        assertEquals(true, gate.update(4f))
        repeat(5) { assertNull(gate.update(-4f)) }
        assertEquals(false, gate.update(-4f))
    }

    @Test
    fun tinyReversalsAndIdleFramesDoNotToggleVisibility() {
        val gate = ScrollDirectionGate(32f, 24f)
        repeat(20) {
            assertNull(gate.update(2f))
            assertNull(gate.update(-2f))
            assertNull(gate.update(0f))
        }
        assertEquals(true, gate.update(32f))
    }
}
