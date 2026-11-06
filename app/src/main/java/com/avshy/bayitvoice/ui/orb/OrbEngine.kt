/*
 * Geometry ported from thinking-orbs 0.3.2 (engine), MIT License,
 * Copyright (c) 2026 Jakub Antalik. https://github.com/Jakubantalik/Libraries.dev
 * Only the four modes the session uses are ported.
 */
package com.avshy.bayitvoice.ui.orb

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** What the orb shows; each maps to one geometry. */
enum class OrbMode { Connecting, Listening, Working, Composing }

/** Tunables of one geometry. Counts of 0 mean "not used by this geometry". */
data class OrbOptions(
    val rings: Int = 0,
    val lonDensity: Int = 0,
    val lanes: Int = 0,
    val segs: Int = 0,
    val orbitN: Int = 0,
    val ghostN: Int = 0,
    val nodeN: Int = 0,
    val signals: Int = 0,
    val particles: Int = 3,
    val rBase: Float = 0.6f,
    val rDepth: Float = 1.7f,
    val ghostR: Float = 0.9f,
    val ghostA: Float = 0.5f,
    val partR: Float = 1.2f,
    val partRDepth: Float = 1.6f,
    val nodeR: Float = 1.4f,
    val nodeRDepth: Float = 1.8f,
    val thr: Float = 0.72f,
    val lineW: Float = 0.8f,
    val spin: Float = 1f,
    val bandMul: Float = 1f,
    val wobMul: Float = 1f,
    val rsPow: Float = 0.6f,
    val rMin: Float = 0.3f,
) {
    /** More or fewer dots, as the engine's `scaleCounts`. */
    fun scaleCounts(scale: Float): OrbOptions {
        val rt = sqrt(scale)
        fun pair(v: Int) = max(2, (v * rt).roundToInt())
        fun single(v: Int) = if (v == 0) 0 else max(1, (v * scale).roundToInt())
        val ringsPair = rings > 0 && lonDensity > 0
        val lanesPair = lanes > 0 && segs > 0
        return copy(
            rings = if (ringsPair) pair(rings) else rings,
            lonDensity = if (ringsPair) pair(lonDensity) else lonDensity,
            lanes = if (lanesPair) pair(lanes) else lanes,
            segs = if (lanesPair) pair(segs) else segs,
            orbitN = single(orbitN),
            ghostN = single(ghostN),
            nodeN = single(nodeN),
            signals = single(signals),
        )
    }

    fun scaleRadii(scale: Float) = copy(
        rBase = rBase * scale, rDepth = rDepth * scale, ghostR = ghostR * scale,
        partR = partR * scale, partRDepth = partRDepth * scale, nodeR = nodeR * scale, nodeRDepth = nodeRDepth * scale,
    )
}

/** A mode's speed and options at the engine's "64" size preset. */
class OrbPreset(val speed: Float, val options: OrbOptions)

object OrbPresets {
    fun of(mode: OrbMode): OrbPreset = when (mode) {
        OrbMode.Working -> OrbPreset(1.885f, OrbOptions(orbitN = 12, ghostN = 40, ghostR = 0.9f, ghostA = 0.5f, particles = 3, partR = 1.2f, partRDepth = 1.6f))
        OrbMode.Listening -> OrbPreset(4.388f, OrbOptions(rings = 15, lonDensity = 40, rBase = 0.6f, rDepth = 1.7f).scaleCounts(0.341f))
        OrbMode.Connecting -> OrbPreset(
            3.315f,
            OrbOptions(nodeN = 30, thr = 0.72f, signals = 5, nodeR = 1.4f, nodeRDepth = 1.8f, lineW = 0.8f).scaleCounts(1.35f).scaleRadii(0.95f),
        )
        OrbMode.Composing -> OrbPreset(
            2.34f,
            OrbOptions(lanes = 5, segs = 88, ghostN = 150, rBase = 1.1f, rDepth = 1.7f).scaleCounts(0.25f).scaleRadii(0.85f)
                .copy(spin = 0f, bandMul = 3.9f, wobMul = 1f),
        )
    }
}

/**
 * One frame of dots and lines in a [size]-px square, reused from frame to frame so drawing
 * allocates nothing. Dots are kept sorted back to front.
 */
class OrbFrame(capacity: Int = 512, lineCapacity: Int = 512) {
    var count = 0; private set
    var x = FloatArray(capacity); private set
    var y = FloatArray(capacity); private set
    var z = FloatArray(capacity); private set
    var r = FloatArray(capacity); private set
    var white = FloatArray(capacity); private set
    var alpha = FloatArray(capacity); private set
    var order = IntArray(capacity); private set

    var lineCount = 0; private set
    var lx1 = FloatArray(lineCapacity); private set
    var ly1 = FloatArray(lineCapacity); private set
    var lx2 = FloatArray(lineCapacity); private set
    var ly2 = FloatArray(lineCapacity); private set
    var lWhite = FloatArray(lineCapacity); private set
    var lAlpha = FloatArray(lineCapacity); private set
    var lWidth = FloatArray(lineCapacity); private set

    fun clear() {
        count = 0
        lineCount = 0
    }

    fun dot(px: Float, py: Float, pz: Float, radius: Float, w: Float, a: Float = 1f) {
        if (a < 0.02f) return
        if (count == x.size) grow()
        x[count] = px; y[count] = py; z[count] = pz; r[count] = radius; white[count] = w; alpha[count] = a
        count++
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float, a: Float, width: Float) {
        if (a < 0.02f) return
        if (lineCount == lx1.size) growLines()
        lx1[lineCount] = x1; ly1[lineCount] = y1; lx2[lineCount] = x2; ly2[lineCount] = y2
        lWhite[lineCount] = w; lAlpha[lineCount] = a; lWidth[lineCount] = width
        lineCount++
    }

    /** Applies the minimum radius and sorts back to front (insertion sort: frames are nearly sorted already). */
    fun finish(rMin: Float) {
        for (i in 0 until count) {
            if (r[i] < rMin) r[i] = rMin
            order[i] = i
        }
        for (i in 1 until count) {
            val o = order[i]
            val key = z[o]
            var j = i - 1
            while (j >= 0 && z[order[j]] > key) {
                order[j + 1] = order[j]
                j--
            }
            order[j + 1] = o
        }
    }

    private fun grow() {
        val n = x.size * 2
        x = x.copyOf(n); y = y.copyOf(n); z = z.copyOf(n); r = r.copyOf(n)
        white = white.copyOf(n); alpha = alpha.copyOf(n); order = order.copyOf(n)
    }

    private fun growLines() {
        val n = lx1.size * 2
        lx1 = lx1.copyOf(n); ly1 = ly1.copyOf(n); lx2 = lx2.copyOf(n); ly2 = ly2.copyOf(n)
        lWhite = lWhite.copyOf(n); lAlpha = lAlpha.copyOf(n); lWidth = lWidth.copyOf(n)
    }
}

/** The geometries. Each fills [out] for time [t] in a [size]-px square. */
object OrbGeometry {

    fun frame(mode: OrbMode, size: Float, t: Float, o: OrbOptions, out: OrbFrame) {
        out.clear()
        when (mode) {
            OrbMode.Working -> orbits(size, t, o, out)
            OrbMode.Listening -> wave(size, t, o, out)
            OrbMode.Connecting -> web(size, t, o, out)
            OrbMode.Composing -> ribbon(size, t, o, out)
        }
        out.finish(o.rMin)
    }

    /** A projection: yaw around Y, then tilt around X, then scale and offset. Writes into [p]. */
    private class Proj(yaw: Float, tilt: Float, private val cx: Float, private val cy: Float, private val scale: Float) {
        private val st = sin(tilt)
        private val ct = cos(tilt)
        private val sy = sin(yaw)
        private val cyw = cos(yaw)
        var px = 0f; var py = 0f; var pz = 0f

        fun project(x: Float, y: Float, z: Float) {
            val x1 = x * cyw + z * sy
            val z1 = -x * sy + z * cyw
            val y1 = y * ct - z1 * st
            val z2 = y * st + z1 * ct
            px = cx + x1 * scale
            py = cy - y1 * scale
            pz = z2
        }
    }

    private fun radiusScale(size: Float, pow: Float) = (size / 300f).pow(pow)

    private fun hashD(a: Float, b: Float): Float {
        val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
        return (h - floor(h)).toFloat()
    }

    private fun frac(x: Float) = x - floor(x)

    private fun vnoise(x: Float, y: Float): Float {
        val xi = floor(x)
        val yi = floor(y)
        var fx = x - xi
        var fy = y - yi
        fx = fx * fx * (3 - 2 * fx)
        fy = fy * fy * (3 - 2 * fy)
        val a = hashD(xi, yi)
        val b = hashD(xi + 1, yi)
        val c = hashD(xi, yi + 1)
        val d = hashD(xi + 1, yi + 1)
        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
    }

    /** Fibonacci sphere direction [i] of [n], into [dir]. */
    private fun fibDir(i: Int, n: Int, dir: FloatArray) {
        val golden = (PI * (3 - sqrt(5.0))).toFloat()
        val y = 1 - 2 * (i + 0.5f) / n
        val rad = sqrt(1 - y * y)
        val a = i * golden
        dir[0] = rad * cos(a); dir[1] = y; dir[2] = rad * sin(a)
    }

    private val dir = FloatArray(3)

    private fun orbits(size: Float, t: Float, o: OrbOptions, out: OrbFrame) {
        val c = size / 2
        val big = size / 2 * 0.82f
        val pt = Proj(t * 0.12f, 0.3f, c, c, 1f)
        val rs = radiusScale(size, o.rsPow)
        for (orb in 0 until o.orbitN) {
            val h1 = hashD(orb.toFloat(), 1.7f)
            val h2 = hashD(orb.toFloat(), 5.2f)
            val h3 = hashD(orb.toFloat(), 8.9f)
            val ro = big * (0.45f + 0.52f * h1)
            val th = h1 * 2 * PI.toFloat()
            val phi = acos(2 * h2 - 1)
            val nx = sin(phi) * cos(th)
            val ny = cos(phi)
            val nz = sin(phi) * sin(th)
            var ux = -ny
            var uy = nx
            val uz = 0f
            val ul = max(1e-6f, sqrt(ux * ux + uy * uy))
            ux /= ul; uy /= ul
            val vx = ny * uz - nz * uy
            val vy = nz * ux - nx * uz
            val vz = nx * uy - ny * ux
            val speed = (0.25f + 0.55f * h3) * (if (h3 > 0.5f) 1 else -1)
            for (k in 0 until o.ghostN) {
                val a = k.toFloat() / o.ghostN * 2 * PI.toFloat()
                pt.project((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (pt.pz / ro + 1) / 2
                out.dot(pt.px, pt.py, pt.pz, o.ghostR * rs, 0.72f, o.ghostA * (0.4f + 0.6f * depth))
            }
            for (m in 0 until o.particles) {
                val a = t * speed + m.toFloat() / o.particles * 2 * PI.toFloat() + h2 * 6
                pt.project((ux * cos(a) + vx * sin(a)) * ro, (uy * cos(a) + vy * sin(a)) * ro, (uz * cos(a) + vz * sin(a)) * ro)
                val depth = (pt.pz / ro + 1) / 2
                out.dot(pt.px, pt.py, pt.pz, (o.partR + o.partRDepth * depth) * rs, 0.3f - 0.22f * depth)
            }
        }
    }

    private fun wave(size: Float, t: Float, o: OrbOptions, out: OrbFrame) {
        val c = size / 2
        val big = size / 2 * 0.874f
        val pt = Proj(t * 0.18f, 0.38f, c, c, 1f)
        val rs = radiusScale(size, o.rsPow)
        for (ri in 0..o.rings) {
            val lat = -PI.toFloat() / 2 + ri.toFloat() / o.rings * PI.toFloat()
            val cosLat = cos(lat)
            val sinLat = sin(lat)
            val w = 0.62f * sin(t * 2.1f - ri * 0.52f) + 0.38f * sin(t * 1.27f + ri * 0.83f)
            val rr = big * (0.88f + 0.105f * w)
            val lonCount = max(1, (abs(cosLat) * o.lonDensity).roundToInt())
            val crest = max(0f, w)
            for (lj in 0 until lonCount) {
                val lon = lj.toFloat() / lonCount * 2 * PI.toFloat()
                pt.project(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
                val depth = (pt.pz / big + 1) / 2
                out.dot(pt.px, pt.py, pt.pz, (o.rBase + o.rDepth * depth) * (1 + 0.4f * crest) * rs, 0.66f - 0.56f * depth - 0.1f * crest)
            }
        }
    }

    private var nodes = FloatArray(0)

    private fun web(size: Float, t: Float, o: OrbOptions, out: OrbFrame) {
        val c = size / 2
        val big = size / 2 * 0.8f
        val pt = Proj(t * 0.12f, 0.32f, c, c, big)
        val rs = radiusScale(size, o.rsPow)
        val n = o.nodeN
        if (nodes.size < n * 3) nodes = FloatArray(n * 3)
        for (i in 0 until n) {
            fibDir(i, n, dir)
            val x = dir[0] + 0.3f * (vnoise(i * 0.31f + 9, t * 0.24f) - 0.5f) * 2
            val y = dir[1] + 0.3f * (vnoise(i * 0.53f + 27, t * 0.21f) - 0.5f) * 2
            val z = dir[2] + 0.3f * (vnoise(i * 0.77f + 55, t * 0.27f) - 0.5f) * 2
            val l = sqrt(x * x + y * y + z * z)
            nodes[i * 3] = x / l; nodes[i * 3 + 1] = y / l; nodes[i * 3 + 2] = z / l
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dx = nodes[i * 3] - nodes[j * 3]
                val dy = nodes[i * 3 + 1] - nodes[j * 3 + 1]
                val dz = nodes[i * 3 + 2] - nodes[j * 3 + 2]
                val dist = sqrt(dx * dx + dy * dy + dz * dz)
                if (dist >= o.thr) continue
                pt.project(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])
                val x1 = pt.px; val y1 = pt.py; val z1 = pt.pz
                pt.project(nodes[j * 3], nodes[j * 3 + 1], nodes[j * 3 + 2])
                val depth = ((z1 + pt.pz) / 2 + 1) / 2
                out.line(x1, y1, pt.px, pt.py, 0.42f, (1 - dist / o.thr) * (0.3f + 0.55f * depth), max(0.6f, o.lineW * rs))
            }
        }
        for (i in 0 until n) {
            pt.project(nodes[i * 3], nodes[i * 3 + 1], nodes[i * 3 + 2])
            val depth = (pt.pz + 1) / 2
            val pulse = 1 + 0.25f * sin(t * 1.4f + i * 2.7f)
            out.dot(pt.px, pt.py, pt.pz, (o.nodeR + o.nodeRDepth * depth) * pulse * rs, 0.55f - 0.45f * depth)
        }
        for (s in 0 until o.signals) {
            val seg = floor(t * 0.55f + s * 7.31f)
            val a = (hashD(seg, s * 3.1f + 1.7f) * n).toInt()
            val b = (hashD(seg, s * 5.7f + 4.2f) * n).toInt()
            if (a == b) continue
            val f = frac(t * 0.55f + s * 7.31f)
            val x = nodes[a * 3] + (nodes[b * 3] - nodes[a * 3]) * f
            val y = nodes[a * 3 + 1] + (nodes[b * 3 + 1] - nodes[a * 3 + 1]) * f
            val z = nodes[a * 3 + 2] + (nodes[b * 3 + 2] - nodes[a * 3 + 2]) * f
            val l = max(1e-6f, sqrt(x * x + y * y + z * z))
            pt.project(x / l, y / l, z / l)
            val depth = (pt.pz + 1) / 2
            out.dot(pt.px, pt.py, pt.pz, (o.nodeR * 1.5f + o.nodeRDepth * depth) * rs, 0.05f, 0.5f + 0.5f * depth)
        }
    }

    private fun ribbon(size: Float, t: Float, o: OrbOptions, out: OrbFrame) {
        val c = size / 2
        val big = size / 2 * 0.78f
        val camTilt = 0.3f
        val pt = Proj(t * 0.1f * o.spin, camTilt, c, c, 1f)
        val rs = radiusScale(size, o.rsPow)
        for (i in 0 until o.ghostN) {
            fibDir(i, o.ghostN, dir)
            pt.project(dir[0] * big, dir[1] * big, dir[2] * big)
            val depth = (pt.pz / big + 1) / 2
            out.dot(pt.px, pt.py, pt.pz, 0.8f * rs, 0.78f, 0.1f + 0.22f * depth)
        }
        val ya = t * 0.24f * o.spin
        val ta = 0.55f + 0.3f * sin(t * 0.18f) * o.spin
        val ux = cos(ya); val uy = 0f; val uz = sin(ya)
        val vx = -uz * sin(ta); val vy = cos(ta); val vz = ux * sin(ta)
        val nx = uy * vz - uz * vy
        val ny = uz * vx - ux * vz
        val nz = ux * vy - uy * vx
        val lanes = max(1, (o.lanes * o.bandMul).roundToInt())
        val half = (lanes - 1) / 2f
        for (w in 0 until lanes) {
            val laneOff = (w - half) * 0.075f
            val edge = abs(w - half) / max(1f, half)
            for (k in 0 until o.segs) {
                val a = k.toFloat() / o.segs * 2 * PI.toFloat()
                val wob = (0.16f * sin(a * 3 - t * 1.7f + w * 0.22f) + 0.07f * sin(a * 5 + t * 1.1f)) * o.wobMul
                val off = laneOff + wob
                val x = ux * cos(a) + vx * sin(a) + nx * off
                val y = uy * cos(a) + vy * sin(a) + ny * off
                val z = uz * cos(a) + vz * sin(a) + nz * off
                val l = sqrt(x * x + y * y + z * z)
                pt.project(x / l * big, y / l * big, z / l * big)
                val depth = (pt.pz / big + 1) / 2
                out.dot(
                    pt.px, pt.py, pt.pz,
                    (o.rBase + o.rDepth * depth) * (1 - 0.25f * edge) * rs,
                    0.52f - 0.44f * depth + 0.18f * edge,
                    0.4f + 0.6f * depth,
                )
            }
        }
    }

    private fun sin(x: Double) = kotlin.math.sin(x)
}
