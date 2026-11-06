package com.avshy.bayitvoice.arbitration

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArbitrationGateTest {

    @After
    fun tearDown() = ArbitrationGate.clear()

    @Test
    fun noArbitrationMeansGoOn() = runBlocking {
        ArbitrationGate.clear()
        assertTrue(ArbitrationGate.awaitWin())
    }

    @Test
    fun sessionWaitsForTheVerdict() = runBlocking {
        ArbitrationGate.begin()
        val result = async { ArbitrationGate.awaitWin() }
        delay(50)
        assertFalse(result.isCompleted)
        ArbitrationGate.resolve(false)
        assertFalse(result.await())
    }

    @Test
    fun verdictReadyBeforeTheSessionAsks() = runBlocking {
        ArbitrationGate.begin()
        ArbitrationGate.resolve(true)
        assertTrue(ArbitrationGate.awaitWin())
    }

    @Test
    fun lostIsVisibleBeforeTheChimeAndIsConsumedOnce() = runBlocking {
        ArbitrationGate.begin()
        ArbitrationGate.resolve(false)
        assertTrue(ArbitrationGate.lost)
        assertFalse(ArbitrationGate.awaitWin())
        // The next session (e.g. a manual one) is not affected by an old result.
        assertTrue(ArbitrationGate.awaitWin())
    }
}
