package com.avshy.bayitvoice.ui.orb

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.Dp
import com.avshy.bayitvoice.audio.MicLevel
import com.avshy.bayitvoice.audio.SpeechLevel
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** What the orb shows: one of the geometries, or its dots gathered into a check mark. */
enum class OrbState(val mode: OrbMode?) {
    Connecting(OrbMode.Connecting),
    Listening(OrbMode.Listening),
    Working(OrbMode.Working),
    Composing(OrbMode.Composing),
    Check(null),
}

/**
 * An animated orb of dots, [orbSize] across, centred in the space the modifier gives it (which
 * may be larger, for the check's stroke). It cross-fades between states; listening follows the
 * microphone and speaking follows the reply's loudness. [countMul] makes it denser.
 */
@Composable
fun Orb(state: OrbState, orbSize: Dp, tint: Color, modifier: Modifier = Modifier, countMul: Float = 1f, speedMul: Float = 1f) {
    val animator = remember(countMul, speedMul) { OrbAnimator(countMul, speedMul) }
    val frame = remember { mutableLongStateOf(0L) }
    animator.target = state

    LaunchedEffect(animator) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 0f else min(0.05f, (now - last) / 1e9f)
                last = now
                animator.step(dt, now / 1e9f)
                frame.longValue = now
            }
        }
    }

    Canvas(modifier) {
        frame.longValue // redraw every frame
        animator.draw(this, orbSize.toPx() / density, tint)
    }
}

/** The orb's state over time: the current and fading layers, and the check-mark animation. */
private class OrbAnimator(private val countMul: Float, private val speedMul: Float) {

    private class Layer(val state: OrbState, val speed: Float, val options: OrbOptions) {
        var t = 0f
    }

    var target: OrbState = OrbState.Connecting
    private var cur: Layer? = null
    private var prev: Layer? = null
    private var fade = 1f
    private var voice = 0f

    private val frame = OrbFrame()
    private var check: CheckMark? = null

    /** The orb size, in dp, of the last frame drawn: the geometry is built at this size. */
    private var sizeDp = 0f

    fun step(dt: Float, nowSec: Float) {
        if (target == OrbState.Check) {
            if (check == null) check = CheckMark.from(sizeDp, frame)
            check?.let { it.time += dt }
            return
        }
        check = null
        val current = cur
        if (current == null || current.state != target) {
            prev = current
            cur = layer(target)
            fade = if (current == null) 1f else 0f
        }
        val level = when (target) {
            OrbState.Composing -> if (SpeechLevel.active.value) SpeechLevel.level.value else syntheticSpeech(nowSec)
            OrbState.Listening -> MicLevel.level
            else -> 0f
        }
        // Quick attack, slower release, so syllables read as pulses rather than flicker.
        voice += (level - voice) * if (level > voice) 0.5f else 0.15f
        val mul = when (target) {
            OrbState.Composing -> 0.35f + 1.6f * voice
            OrbState.Listening -> 0.5f + 1.1f * voice
            else -> 1f
        }
        cur?.let { it.t += dt * it.speed * mul }
        prev?.let { it.t += dt * it.speed }
        fade = min(1f, fade + dt / 0.35f)
        if (fade >= 1f) prev = null
    }

    private fun layer(state: OrbState): Layer {
        val preset = OrbPresets.of(state.mode!!)
        return Layer(state, preset.speed * speedMul, preset.options.scaleCounts(countMul))
    }

    fun draw(scope: DrawScope, orbSizeDp: Float, tint: Color) = with(scope) {
        sizeDp = orbSizeDp
        val k = check
        if (k != null) {
            k.draw(this, tint)
            return@with
        }
        val e = fade * fade * (3 - 2 * fade)
        prev?.let { paintLayer(it, 1 - e, 1 - 0.08f * e, tint) }
        cur?.let { paintLayer(it, e, 0.92f + 0.08f * e, tint) }
    }

    private fun DrawScope.paintLayer(layer: Layer, alpha: Float, scale: Float, tint: Color) {
        OrbGeometry.frame(layer.state.mode!!, sizeDp, layer.t, layer.options, frame)
        orbSpace(sizeDp, scale) { paintFrame(frame, tint, alpha) }
    }
}

/** Runs [block] in the orb's own coordinates: dp units, [sizeDp] square, centred in the canvas. */
private inline fun DrawScope.orbSpace(sizeDp: Float, scale: Float, block: DrawScope.() -> Unit) {
    val k = density * scale
    val dx = (size.width - sizeDp * k) / 2
    val dy = (size.height - sizeDp * k) / 2
    withTransform({
        translate(dx, dy)
        scale(k, k, pivot = Offset.Zero)
    }, block)
}

/** Dark ink on the tint: lighter dots ("white" near 0) are closer to the tint colour. */
private fun ink(tint: Color, white: Float, alpha: Float): Color {
    val f = 1 - white.coerceIn(0f, 1f)
    return Color(tint.red * f, tint.green * f, tint.blue * f, alpha.coerceIn(0f, 1f))
}

private fun DrawScope.paintFrame(frame: OrbFrame, tint: Color, layerAlpha: Float) {
    for (i in 0 until frame.lineCount) {
        drawLine(
            ink(tint, frame.lWhite[i], frame.lAlpha[i] * layerAlpha),
            Offset(frame.lx1[i], frame.ly1[i]), Offset(frame.lx2[i], frame.ly2[i]),
            strokeWidth = frame.lWidth[i],
        )
    }
    for (n in 0 until frame.count) {
        val i = frame.order[n]
        drawCircle(ink(tint, frame.white[i], frame.alpha[i] * layerAlpha), frame.r[i], Offset(frame.x[i], frame.y[i]))
    }
}

/** A loop of syllables and pauses, for when the device does not report the reply's loudness. */
private fun syntheticSpeech(t: Float): Float {
    val syl = max(0f, sin(t * 11)) * (0.55f + 0.45f * sin(t * 2.3f))
    val pause = if (sin(t * 0.9f) > -0.6f) 1f else 0.15f
    return min(1f, syl * pause + 0.08f)
}

/**
 * Done: the orb's own dots fly into a check mark, then the mark fills in as one stroke.
 * Timeline: 0–0.8 s the dots fly in; 0.7–1.0 s the solid stroke fades in; then it holds.
 */
private class CheckMark private constructor(
    private val sizeDp: Float,
    private val fromX: FloatArray, private val fromY: FloatArray, private val fromR: FloatArray,
    private val fromWhite: FloatArray, private val fromAlpha: FloatArray,
    private val toX: FloatArray, private val toY: FloatArray,
    private val points: FloatArray, private val stroke: Float,
) {
    var time = 0f

    fun draw(scope: DrawScope, tint: Color) = with(scope) {
        val e = easeInOutCubic((time / 0.8f).coerceIn(0f, 1f))
        val solid = ((time - 0.7f) / 0.3f).coerceIn(0f, 1f)
        val rT = stroke / 2
        orbSpace(sizeDp, 1f) {
            if (solid < 1f) {
                for (i in fromX.indices) {
                    drawCircle(
                        ink(tint, fromWhite[i] + (0.12f - fromWhite[i]) * e, (fromAlpha[i] + (1 - fromAlpha[i]) * e) * (1 - solid)),
                        fromR[i] + (rT - fromR[i]) * e,
                        Offset(fromX[i] + (toX[i] - fromX[i]) * e, fromY[i] + (toY[i] - fromY[i]) * e),
                    )
                }
            }
            if (solid > 0f) {
                val color = tint.copy(alpha = solid)
                val p = points
                drawLine(color, Offset(p[0], p[1]), Offset(p[2], p[3]), stroke, StrokeCap.Round)
                drawLine(color, Offset(p[2], p[3]), Offset(p[4], p[5]), stroke, StrokeCap.Round)
            }
        }
    }

    companion object {
        /** Starts from the dots of the last frame drawn, still in [frame]. */
        fun from(sizeDp: Float, frame: OrbFrame): CheckMark {
            val n = frame.count
            // The last frame drawn is still in [frame]; take its dots in back-to-front order.
            val fx = FloatArray(n); val fy = FloatArray(n); val fr = FloatArray(n); val fw = FloatArray(n); val fa = FloatArray(n)
            for (k in 0 until n) {
                val i = frame.order[k]
                fx[k] = frame.x[i]; fy[k] = frame.y[i]; fr[k] = frame.r[i]; fw[k] = frame.white[i]; fa[k] = frame.alpha[i]
            }
            // The status icon's check (24-unit grid), centred in the orb.
            val s = sizeDp * 0.62f / 14
            val grid = floatArrayOf(5f, 12.5f, 9.5f, 17f, 19f, 7.5f)
            val p = FloatArray(6) { i -> sizeDp / 2 + (grid[i] - if (i % 2 == 0) 12f else 12.25f) * s }
            val seg1 = hypot(p[2] - p[0], p[3] - p[1])
            val seg2 = hypot(p[4] - p[2], p[5] - p[3])
            val total = seg1 + seg2
            val stroke = 2.6f * s * if (sizeDp > 200) 0.45f else 0.7f
            val tx = FloatArray(n); val ty = FloatArray(n)
            for (i in 0 until n) {
                val u = if (n > 1) i.toFloat() / (n - 1) * total else 0f
                val (ax, ay, bx, by, f, len) = if (u <= seg1) Seg(p[0], p[1], p[2], p[3], u / seg1, seg1) else Seg(p[2], p[3], p[4], p[5], (u - seg1) / seg2, seg2)
                val nx = -(by - ay) / len
                val ny = (bx - ax) / len
                val j = (sin(i * 12.9898) * 43758.5453 % 1).toFloat()
                val w = j * stroke * 0.25f
                tx[i] = ax + (bx - ax) * f + nx * w
                ty[i] = ay + (by - ay) * f + ny * w
            }
            // Pair dots left to right with targets left to right, so paths cross as little as possible.
            val fromOrder = (0 until n).sortedBy { fx[it] }
            val toOrder = (0 until n).sortedBy { tx[it] }
            val toXs = FloatArray(n); val toYs = FloatArray(n)
            for (m in 0 until n) {
                toXs[fromOrder[m]] = tx[toOrder[m]]
                toYs[fromOrder[m]] = ty[toOrder[m]]
            }
            return CheckMark(sizeDp, fx, fy, fr, fw, fa, toXs, toYs, p, stroke)
        }

        private data class Seg(val ax: Float, val ay: Float, val bx: Float, val by: Float, val f: Float, val len: Float)

        private fun easeInOutCubic(x: Float) = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

        private fun sin(x: Double) = kotlin.math.sin(x)
    }
}

