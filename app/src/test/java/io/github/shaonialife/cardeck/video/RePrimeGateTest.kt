package io.github.shaonialife.cardeck.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Re-prime ordering for adapter re-entry: config + IDR, and nothing of the old GOP after them. */
class RePrimeGateTest {
    @Test
    fun framesPassUntouchedWithoutARequest() {
        val gate = RePrimeGate()
        assertEquals(RePrimeGate.Decision.SEND, gate.onFrame(keyFrame = false, nowMillis = 0))
        assertEquals(RePrimeGate.Decision.SEND, gate.onFrame(keyFrame = true, nowMillis = 1))
    }

    @Test
    fun oldGopFramesAreHeldBackUntilTheIdr() {
        val gate = RePrimeGate()
        gate.request(1_000)
        assertTrue(gate.pending)
        assertEquals(RePrimeGate.Decision.DROP, gate.onFrame(keyFrame = false, nowMillis = 1_033))
        assertEquals(RePrimeGate.Decision.DROP, gate.onFrame(keyFrame = false, nowMillis = 1_066))
        assertEquals(RePrimeGate.Decision.SEND_WITH_CONFIG, gate.onFrame(keyFrame = true, nowMillis = 1_100))
        assertFalse(gate.pending)
        // The frames after the IDR reference it: sent normally.
        assertEquals(RePrimeGate.Decision.SEND, gate.onFrame(keyFrame = false, nowMillis = 1_133))
    }

    @Test
    fun aLostRequestStopsHoldingBackAfterTheWindow() {
        val gate = RePrimeGate(maxWaitMillis = 500)
        gate.request(0)
        assertEquals(RePrimeGate.Decision.DROP, gate.onFrame(keyFrame = false, nowMillis = 499))
        assertEquals(RePrimeGate.Decision.SEND_WITH_CONFIG_TIMEOUT, gate.onFrame(keyFrame = false, nowMillis = 500))
        assertFalse(gate.pending)
    }

    @Test
    fun repeatedRequestsKeepTheFirstDeadline() {
        val gate = RePrimeGate(maxWaitMillis = 500)
        gate.request(0)
        gate.request(400)
        assertEquals(RePrimeGate.Decision.SEND_WITH_CONFIG_TIMEOUT, gate.onFrame(keyFrame = false, nowMillis = 500))
    }
}
