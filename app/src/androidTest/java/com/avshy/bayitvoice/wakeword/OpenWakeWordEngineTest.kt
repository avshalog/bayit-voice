package com.avshy.bayitvoice.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** Loads every built-in model on a real device and runs audio through the full pipeline. */
@RunWith(AndroidJUnit4::class)
class OpenWakeWordEngineTest {

    private val models = WakeWordModels(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun allBuiltInModelsLoadAndScore() {
        for (model in WakeWordModels.BUILT_IN) {
            models.createEngine(model).use { engine ->
                val frame = ShortArray(engine.frameSamples)
                var maxScore = 0f
                // 3 s of a 440 Hz tone: not a wake word, so scores must stay valid and low.
                for (i in 0 until 38) {
                    for (s in frame.indices) {
                        val t = (i * frame.size + s) / 16_000.0
                        frame[s] = (3000 * sin(2 * PI * 440 * t)).toInt().toShort()
                    }
                    val score = engine.process(frame)
                    assertTrue("${model.name}: score $score out of range", score in 0f..1f)
                    maxScore = maxOf(maxScore, score)
                }
                assertTrue("${model.name}: tone scored $maxScore", maxScore < 0.5f)
            }
        }
    }
}
