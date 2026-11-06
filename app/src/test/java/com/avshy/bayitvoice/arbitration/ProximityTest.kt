package com.avshy.bayitvoice.arbitration

import com.avshy.bayitvoice.session.WakeDetection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityTest {

    private fun detection(snrDb: Float, score: Float = 0.8f) = WakeDetection(0L, score, snrDb, 100.0)

    @Test
    fun averagesWakeWordAndCommandLevels() {
        assertEquals(20f + 8f, SessionArbitration.proximity(detection(30f), 10f), 0.001f)
    }

    @Test
    fun usesTheWakeWordAloneWhenNothingWasRecorded() {
        assertEquals(30f + 8f, SessionArbitration.proximity(detection(30f), null), 0.001f)
    }

    @Test
    fun theCommandCanOverturnAWakeWordCloseCall() {
        // The far device heard the wake word slightly louder, but the near one hears the command far better.
        val near = SessionArbitration.proximity(detection(24f), 22f)
        val far = SessionArbitration.proximity(detection(26f), 8f)
        assertTrue(near > far)
    }
}
