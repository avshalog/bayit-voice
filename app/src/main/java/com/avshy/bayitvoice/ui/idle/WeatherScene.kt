package com.avshy.bayitvoice.ui.idle

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.withFrameNanos
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The idle screen's weather scene (SPEC section 11), ported from the approved design
 * (`docs/idle-screen-demo.html`): a gradient sky, layered ridges fading into haze, Mediterranean
 * woodland in front, soft drifting clouds, sun or moon, and rain, snow, lightning or fog.
 *
 * All geometry and gradients are built once per size and [spec]; a frame only moves things, at
 * about 30 fps, without allocating. Frames stop by themselves while the screen is not visible.
 */
@Composable
fun WeatherScene(spec: SceneSpec, modifier: Modifier = Modifier) {
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var start = -1L
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (start < 0) start = now
                if (now - last >= FRAME_NS) {
                    last = now
                    time.floatValue = (now - start) / 1e9f
                }
            }
        }
    }
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val scene = Scene(spec, size, density, direction)
            onDrawBehind { scene.draw(this, time.floatValue) }
        },
    )
}

/** About 20 frames a second: the motion is slow, and this keeps an always-on screen light. */
private const val FRAME_NS = 50_000_000L

/** Everything that decides how a scene looks. Built from HA's condition by [SceneSpec.of]. */
data class SceneSpec(
    val sky: List<Color>,
    val sun: Float = 0f,
    val sunColor: Color = Color(0xFFFFEEC8),
    val sunX: Float = 0.44f,
    val sunY: Float = 0.44f,
    val rays: Float = 0f,
    val moon: Float = 0f,
    val stars: Float = 0f,
    val clouds: Float = 0f,
    val cloudTint: Color = Color.White,
    val cloudSpeed: Float = 1f,
    val rain: Float = 0f,
    val snow: Float = 0f,
    val lightning: Boolean = false,
    val fog: Boolean = false,
    val birds: Boolean = false,
    val far: Color,
    val near: Color,
    val mist: Color,
) {
    companion object {
        private val SUNNY = SceneSpec(
            sky = listOf(Color(0xFF3C7FD6), Color(0xFF8FC0EE), Color(0xFFF4E9D2)),
            sun = 1f, rays = 1f, clouds = 0.25f, cloudTint = Color(0xFFFFFCF6), birds = true,
            far = Color(0xFFA9C2D6), near = Color(0xFF1D3A2F), mist = Color(0xFFF0ECE2),
        )
        private val PARTLY = SceneSpec(
            sky = listOf(Color(0xFF4A7EC4), Color(0xFF9CC1E4), Color(0xFFE9E4D8)),
            sun = 0.8f, sunX = 0.42f, sunY = 0.42f, rays = 0.6f, clouds = 0.6f, cloudTint = Color(0xFFFCFCFC), birds = true,
            far = Color(0xFFA7BCCB), near = Color(0xFF20392F), mist = Color(0xFFE8E8E4),
        )
        private val CLOUDY = SceneSpec(
            sky = listOf(Color(0xFF7C8794), Color(0xFFA3ACB6), Color(0xFFCDD1D4)),
            clouds = 1f, cloudTint = Color(0xFFE4E7EB),
            far = Color(0xFFA3ABB2), near = Color(0xFF2A352F), mist = Color(0xFFD6DADE),
        )
        private val RAIN = SceneSpec(
            sky = listOf(Color(0xFF3D4551), Color(0xFF5E6874), Color(0xFF8A929B)),
            clouds = 1f, cloudTint = Color(0xFF98A0AA), rain = 1f,
            far = Color(0xFF7D868E), near = Color(0xFF1C2622), mist = Color(0xFF969EA6),
        )
        private val STORM = SceneSpec(
            sky = listOf(Color(0xFF1B202B), Color(0xFF323A48), Color(0xFF545D6B)),
            clouds = 1f, cloudTint = Color(0xFF606876), cloudSpeed = 2f, rain = 1.5f, lightning = true,
            far = Color(0xFF4F5865), near = Color(0xFF111816), mist = Color(0xFF606874),
        )
        private val SNOW = SceneSpec(
            sky = listOf(Color(0xFF8E98A4), Color(0xFFB5BDC6), Color(0xFFDDE1E4)),
            clouds = 1f, cloudTint = Color(0xFFEEF0F2), snow = 1f,
            far = Color(0xFFC2CAD0), near = Color(0xFF4A5652), mist = Color(0xFFE6E9EC),
        )
        private val FOG = SceneSpec(
            sky = listOf(Color(0xFFA4ACB3), Color(0xFFC2C8CC), Color(0xFFDCDFE0)),
            clouds = 0.2f, cloudTint = Color(0xFFECEEF0), fog = true,
            far = Color(0xFFCFD3D5), near = Color(0xFF4C5752), mist = Color(0xFFE2E5E7),
        )
        private val SUNSET = SceneSpec(
            sky = listOf(Color(0xFF2B3A6E), Color(0xFFC26A73), Color(0xFFF6B35C)),
            sun = 1f, sunColor = Color(0xFFFFC478), sunX = 0.46f, sunY = 0.6f, rays = 0.7f,
            clouds = 0.3f, cloudTint = Color(0xFFFFC4AA), birds = true,
            far = Color(0xFF9A6F86), near = Color(0xFF1A1625), mist = Color(0xFFF6B280),
        )
        private val NIGHT = SceneSpec(
            sky = listOf(Color(0xFF050A1A), Color(0xFF122049), Color(0xFF2D3C6C)),
            moon = 1f, stars = 1f, clouds = 0.15f, cloudTint = Color(0xFF6E7CA8),
            far = Color(0xFF2A3962), near = Color(0xFF060A12), mist = Color(0xFF3C4C78),
        )

        /** A night version of a cloudy or wet scene: the same weather, darker and bluer. */
        private fun SceneSpec.atNight() = copy(
            sky = sky.map { lerp(it, Color(0xFF070B18), 0.72f) },
            cloudTint = lerp(cloudTint, Color(0xFF2A3350), 0.65f),
            far = lerp(far, Color(0xFF1A2440), 0.7f),
            near = lerp(near, Color(0xFF05080E), 0.6f),
            mist = lerp(mist, Color(0xFF2A3350), 0.7f),
            sun = 0f, rays = 0f, birds = false,
        )

        /**
         * Maps HA's weather condition and the sun's elevation (degrees; null = unknown, judged
         * by [fallbackNight]) to a scene. Twilight is between -4° and 6°.
         */
        fun of(condition: String?, sunElevation: Double?, fallbackNight: Boolean): SceneSpec {
            val night = sunElevation?.let { it < -4 } ?: fallbackNight
            val twilight = sunElevation?.let { it in -4.0..6.0 } ?: false
            val clear = when {
                night -> NIGHT
                twilight -> SUNSET
                else -> SUNNY
            }
            fun wet(day: SceneSpec) = if (night) day.atNight() else day
            return when (condition) {
                null, "sunny" -> clear
                "clear-night" -> NIGHT
                "partlycloudy" -> when {
                    night -> NIGHT.copy(moon = 0.7f, stars = 0.5f, clouds = 0.7f)
                    twilight -> SUNSET.copy(clouds = 0.65f)
                    else -> PARTLY
                }
                "windy", "windy-variant" -> when {
                    night -> NIGHT.copy(clouds = 0.6f, cloudSpeed = 3f)
                    else -> PARTLY.copy(cloudSpeed = 3f, birds = false)
                }
                "cloudy", "exceptional" -> wet(CLOUDY)
                "fog" -> wet(FOG)
                "rainy" -> wet(RAIN)
                "pouring" -> wet(RAIN.copy(rain = 1.7f))
                "lightning", "lightning-rainy" -> wet(STORM)
                "snowy", "snowy-rainy", "hail" -> wet(if (condition == "snowy-rainy") SNOW.copy(rain = 0.5f) else SNOW)
                else -> clear
            }
        }
    }
}

/** A built scene for one size: all paths, brushes and pre-rendered clouds. */
private class Scene(
    private val s: SceneSpec,
    private val size: Size,
    private val density: Density,
    private val direction: LayoutDirection,
) {

    private val w = size.width
    private val h = size.height
    /** Sizes of things (sun, trees, drops) follow this unit, as the text does, so an upright screen keeps them in scale. */
    private val u = min(h, w * 0.45f)
    private val rnd = Random(11)

    private val skyBrush = Brush.verticalGradient(
        0f to s.sky[0], 0.55f to s.sky[1], 1f to s.sky[2], startY = 0f, endY = h * 0.75f,
    )
    private val sunCenter = Offset(w * s.sunX, h * s.sunY)
    private val sunR = u * 0.07f
    private val sunGlow = Brush.radialGradient(
        0f to s.sunColor.copy(alpha = 0.55f * s.sun), 0.2f to s.sunColor.copy(alpha = 0.2f * s.sun),
        1f to s.sunColor.copy(alpha = 0f), center = sunCenter, radius = h * 0.9f,
    )
    private val sunCore = Brush.radialGradient(
        0f to Color(0xFFFFFFF8).copy(alpha = s.sun), 0.6f to s.sunColor.copy(alpha = s.sun),
        1f to s.sunColor.copy(alpha = 0f), center = sunCenter, radius = sunR * 1.4f,
    )
    private val rayPath = Path().apply {
        moveTo(sunCenter.x, sunCenter.y)
        lineTo(sunCenter.x + w * 0.9f, sunCenter.y - h * 0.05f)
        lineTo(sunCenter.x + w * 0.9f, sunCenter.y + h * 0.05f)
        close()
    }
    private val rayBrush = Brush.horizontalGradient(
        0f to s.sunColor.copy(alpha = 0.1f * s.rays * s.sun), 1f to s.sunColor.copy(alpha = 0f),
        startX = sunCenter.x, endX = sunCenter.x + w * 0.9f,
    )
    private val moonCenter = Offset(w * 0.44f, h * 0.34f)
    private val moonR = u * 0.05f

    /** The crescent: the moon's disc minus an offset disc, so the sky's gradient shows through, not a flat colour. */
    private val moonPath = Path().apply {
        val disc = Path().apply { addOval(Rect(moonCenter, moonR)) }
        val cut = Path().apply { addOval(Rect(Offset(moonCenter.x + moonR * 0.5f, moonCenter.y - moonR * 0.18f), moonR * 0.9f)) }
        op(disc, cut, PathOperation.Difference)
    }
    private val moonGlow = Brush.radialGradient(
        0f to Color(0x4DC4D4FF), 1f to Color(0x00C4D4FF), center = moonCenter, radius = moonR * 8f,
    )

    private val stars = FloatArray(if (s.stars > 0) (260 * s.stars).toInt() * 5 else 0).also { a ->
        for (i in 0 until a.size / 5) {
            a[i * 5] = rnd.nextFloat() * w
            a[i * 5 + 1] = rnd.nextFloat() * h * 0.6f
            a[i * 5 + 2] = 0.3f + rnd.nextFloat() * rnd.nextFloat() * 1.6f
            a[i * 5 + 3] = rnd.nextFloat() * 6.28f
            a[i * 5 + 4] = 0.4f + rnd.nextFloat() * 1.4f
        }
    }

    private class Cloud(val image: ImageBitmap, val x: Float, val y: Float, val speed: Float, val alpha: Float, val depth: Float)

    private val clouds: List<Cloud> = run {
        val n = (2 + s.clouds * 10).roundToInt()
        List(n) {
            val depth = rnd.nextFloat()
            val cw = w * (0.2f + 0.32f * depth) * (0.8f + rnd.nextFloat() * 0.5f)
            val shade = (if (s.rain > 0) 0.45f else 0.18f) * (0.4f + depth * 0.6f)
            Cloud(
                image = renderCloud(cw, s.cloudTint, shade, density, direction),
                x = rnd.nextFloat() * (w + cw),
                y = h * (0.02f + 0.34f * rnd.nextFloat()) - if (s.clouds > 0.9f) h * 0.06f else 0f,
                speed = (3 + depth * 9) * s.cloudSpeed * (w / 900f),
                alpha = (0.5f + depth * 0.5f) * min(1f, 0.35f + s.clouds),
                depth = depth,
            )
        }.sortedBy { it.depth }
    }

    private fun renderCloud(cw: Float, tint: Color, shade: Float, density: Density, direction: LayoutDirection): ImageBitmap {
        val ch = cw * 0.36f
        val pad = cw * 0.3f
        val bw = (cw + pad * 2).toInt().coerceAtLeast(1)
        val bh = (ch + pad * 2).toInt().coerceAtLeast(1)
        val image = ImageBitmap(bw, bh)
        val dark = lerp(tint, Color(0xFF282E3C), shade)
        val mid = lerp(tint, dark, 0.5f)
        CanvasDrawScope().draw(density, direction, Canvas(image), Size(bw.toFloat(), bh.toFloat())) {
            repeat(26) {
                val u = rnd.nextFloat()
                val arch = sin(u * PI).toFloat()
                val x = pad + cw * (0.06f + 0.88f * u)
                val y = pad + ch * (0.82f - 0.5f * arch * (0.5f + 0.5f * rnd.nextFloat()))
                val r = cw * (0.07f + 0.12f * arch * (0.6f + rnd.nextFloat() * 0.6f))
                drawCircle(
                    Brush.radialGradient(
                        0f to tint.copy(alpha = 0.9f), 0.6f to mid.copy(alpha = 0.55f), 1f to dark.copy(alpha = 0f),
                        center = Offset(x, y - r * 0.45f), radius = r,
                    ),
                    radius = r, center = Offset(x, y),
                )
            }
        }
        return image
    }

    // Rain or snow: x, y, length or size, speed, depth.
    private val drops = FloatArray(((420 * s.rain) + (220 * s.snow)).toInt() * 5).also { a ->
        for (i in 0 until a.size / 5) {
            a[i * 5] = rnd.nextFloat() * w * 1.3f
            a[i * 5 + 1] = rnd.nextFloat() * h
            a[i * 5 + 2] = 14f + rnd.nextFloat() * 26f
            a[i * 5 + 3] = 700f + rnd.nextFloat() * 500f
            a[i * 5 + 4] = rnd.nextFloat()
        }
    }
    private val snowCount = (220 * s.snow).toInt()
    private val dropScale = u / 600f

    private val birds = FloatArray(if (s.birds) 5 * 4 else 0).also { a ->
        for (i in 0 until a.size / 4) {
            a[i * 4] = rnd.nextFloat() * 60f
            a[i * 4 + 1] = rnd.nextFloat() * 30f
            a[i * 4 + 2] = rnd.nextFloat() * 6f
            a[i * 4 + 3] = 0.7f + rnd.nextFloat() * 0.5f
        }
    }
    private val birdPath = Path()

    /** The light's side for shading crowns: the sun's or moon's x. */
    private val lightX = if (s.sun > 0) sunCenter.x else if (s.moon > 0) moonCenter.x else w * 0.5f
    private val light = if (s.sun > 0) s.sunColor else s.mist
    private val litK = if (s.sun > 0) 0.32f else if (s.moon > 0) 0.14f else 0.1f

    private class Tree(
        val crown: Path,
        val crownBrush: Brush,
        val shadeBrush: Brush,
        val trunk: Path?,
        val trunkColor: Color,
        val shadowTopLeft: Offset,
        val shadowSize: Size,
    )
    private class Ridge(val path: Path, val brush: Brush, val trees: List<Tree>, val mistTop: Float, val mistBrush: Brush?)

    private val ridges: List<Ridge> = run {
        val modes = listOf(
            RidgeSpec(0.62f, 0.16f, 2.2f, 0f, false, 3),
            RidgeSpec(0.70f, 0.13f, 2.8f, 0f, true, 17),
            RidgeSpec(0.78f, 0.11f, 3.4f, 0f, true, 29),
            RidgeSpec(0.87f, 0.09f, 4.0f, 0.07f, false, 41),
            RidgeSpec(1.0f, 0.08f, 4.6f, 0.17f, false, 53),
        )
        modes.mapIndexed { i, m ->
            val k = i / (modes.size - 1f)
            val col = lerp(s.far, s.near, k.pow(0.8f))
            val top = lerp(col, lerp(s.far, s.mist, 0.4f), 0.25f * (1 - k))
            buildRidge(m, col, top, (if (s.fog) 0.55f else 0.22f) * (1 - k * 0.7f))
        }
    }

    private class RidgeSpec(val base: Float, val amp: Float, val freq: Float, val trees: Float, val treeline: Boolean, val seed: Int)

    private fun buildRidge(m: RidgeSpec, col: Color, top: Color, mistAmount: Float): Ridge {
        val n = Noise(rnd, m.seed)
        val canopy = Noise(rnd, m.seed + 5)
        val step = max(3f, w / 600f)
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        var x = -4f
        while (x <= w + 8) {
            var y = h * (m.base - m.amp * n.fbm(x / w * m.freq))
            if (m.treeline || m.trees > 0) {
                val scale = if (m.treeline) 0.016f else m.trees * 0.09f
                val cover = if (canopy(x / w * 9) > 0.32f) 1f else 0.25f
                y -= u * scale * cover * sqrt(abs(sin(x / (u * scale * 1.6f + 2) + canopy((x / w * 30) % 60) * 3)))
            }
            xs += x; ys += y; x += step
        }
        val path = Path().apply {
            moveTo(-4f, h)
            for (i in xs.indices) lineTo(xs[i], ys[i])
            lineTo(w + 8, h)
            close()
        }
        val minY = ys.minOrNull() ?: h
        val brush = Brush.verticalGradient(0f to top, 1f to col, startY = minY, endY = h)
        fun yAt(px: Float) = ys[((px + 4) / step).roundToInt().coerceIn(0, ys.size - 1)]

        val trees = ArrayList<Tree>()
        if (m.trees > 0) {
            var tx = -u * m.trees * 0.4f
            while (tx < w + u * m.trees) {
                val r = rnd.nextFloat()
                val th = u * m.trees * (0.55f + rnd.nextFloat() * 0.7f)
                val ty = yAt(tx) + th * 0.04f
                trees += buildTree(tx, ty, th, r, col)
                tx += max(4f, th * if (rnd.nextFloat() < 0.2f) 1.1f + rnd.nextFloat() * 1.6f else 0.35f + rnd.nextFloat() * 0.55f)
            }
        }
        val mistBrush = if (mistAmount > 0) Brush.verticalGradient(
            0f to s.mist.copy(alpha = 0f), 1f to s.mist.copy(alpha = mistAmount), startY = h * 0.5f, endY = h,
        ) else null
        return Ridge(path, brush, trees.sortedBy { it.shadowTopLeft.y }, minY, mistBrush)
    }

    private fun buildTree(x: Float, y: Float, th: Float, r: Float, layer: Color): Tree {
        val kind = when { r < 0.5f -> 0; r < 0.78f -> 1; r < 0.92f -> 2; else -> 3 } // oak, bush, pine, cypress
        val tone = (rnd.nextFloat() - 0.5f) * 0.18f
        val base = lerp(layer, foliage[rnd.nextInt(3)], 0.18f)
        val c = if (tone > 0) lerp(base, Color.White, tone * 0.35f) else lerp(base, Color.Black, -tone)
        val lean = (rnd.nextFloat() - 0.5f) * th * 0.12f
        val crown = when (kind) {
            0 -> crown(x + lean, y - th * 0.6f, th * (0.45f + rnd.nextFloat() * 0.18f), th * (0.36f + rnd.nextFloat() * 0.1f), 0)
            1 -> crown(x, y - th * 0.16f, th * (0.32f + rnd.nextFloat() * 0.16f), th * (0.2f + rnd.nextFloat() * 0.06f), 0)
            2 -> crown(x + lean * 2.5f, y - th * 0.9f, th * (0.42f + rnd.nextFloat() * 0.16f), th * (0.14f + rnd.nextFloat() * 0.05f), 1)
            else -> crown(x, y - th * 0.62f, th * (0.09f + rnd.nextFloat() * 0.03f), th * 0.6f, 2)
        }
        val (path, bounds) = crown
        val side = sign(lightX - x).takeIf { it != 0f } ?: 1f
        val lit = lerp(c, light, litK)
        val dark = lerp(c, Color.Black, 0.22f)
        val crownBrush = Brush.linearGradient(
            0f to lit, 0.55f to c, 1f to dark,
            start = Offset(if (side > 0) bounds[2] else bounds[0], bounds[1]),
            end = Offset(if (side > 0) bounds[0] else bounds[2], bounds[3]),
        )
        val trunk = if (kind == 0 || kind == 2) {
            val topY = if (kind == 2) bounds[3] - th * 0.02f else (bounds[1] + bounds[3]) / 2
            val tx = if (kind == 2) x + lean * 2.5f else x + lean
            val tw = th * if (kind == 2) 0.028f else 0.05f
            Path().apply {
                moveTo(x - tw, y)
                quadraticTo((x + tx) / 2 - tw * 0.6f, (y + topY) / 2, tx - tw * 0.45f, topY)
                lineTo(tx + tw * 0.45f, topY)
                quadraticTo((x + tx) / 2 + tw * 0.6f, (y + topY) / 2, x + tw, y)
                close()
            }
        } else null
        val shadowW = bounds[2] - bounds[0]
        // Darker toward the underside of the crown.
        val shadeBrush = Brush.verticalGradient(
            0f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0x38000000), startY = bounds[1], endY = bounds[3],
        )
        return Tree(
            crown = path, crownBrush = crownBrush, shadeBrush = shadeBrush, trunk = trunk, trunkColor = lerp(c, Color(0xFF1E1A16), 0.55f),
            shadowTopLeft = Offset(x - side * th * 0.18f - shadowW / 2, y - th * 0.05f),
            shadowSize = Size(shadowW, th * 0.1f),
        )
    }

    /** One crown outline: an ellipse with a wobbling radius and a flatter base. 0 round, 1 umbrella, 2 flame. */
    private fun crown(cx: Float, cy: Float, rx: Float, ry: Float, shape: Int): Pair<Path, FloatArray> {
        val a0 = rnd.nextFloat() * 6.28f; val a1 = rnd.nextFloat() * 6.28f; val a2 = rnd.nextFloat() * 6.28f
        val path = Path()
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until 72) {
            val t = i / 72f * 6.2832f
            val sn = sin(t); val cs = cos(t)
            val r = 1 + 0.09f * sin(3 * t + a0) + 0.06f * sin(5 * t + a1) + 0.035f * sin(13 * t + a2) + (rnd.nextFloat() - 0.5f) * 0.045f
            var px = cs * rx * r
            var py = sn * ry * r
            if (sn > 0) py *= if (shape == 1) 0.45f else 0.62f
            if (shape == 2) px *= 1 - 0.55f * max(0f, -sn)
            val fx = cx + px; val fy = cy + py
            if (i == 0) path.moveTo(fx, fy) else path.lineTo(fx, fy)
            b[0] = min(b[0], fx); b[1] = min(b[1], fy); b[2] = max(b[2], fx); b[3] = max(b[3], fy)
        }
        path.close()
        return path to b
    }

    private val fogBands: List<Pair<Float, Brush>> = if (!s.fog) emptyList() else List(5) { i ->
        val y = h * (0.45f + i * 0.11f)
        y to Brush.verticalGradient(
            0f to s.mist.copy(alpha = 0f), 0.5f to s.mist.copy(alpha = 0.42f - i * 0.05f), 1f to s.mist.copy(alpha = 0f),
            startY = y - h * 0.1f, endY = y + h * 0.1f,
        )
    }

    private val vignette = Brush.verticalGradient(
        0f to Color(0x4D000000), 0.42f to Color.Transparent, 0.8f to Color.Transparent, 1f to Color(0x59000000),
    )

    // Lightning: a new bolt every 4–10 s, fading over about half a second.
    private val bolt = Path()
    private var nextFlash = 1.5f
    private var flashAt = -10f

    // What never moves is drawn once into two images, so a frame draws a few images and the
    // moving things instead of hundreds of ridge and tree shapes (this cut CPU use several-fold).
    private val background: ImageBitmap = render {
        drawRect(skyBrush)
        if (s.sun > 0) drawRect(sunGlow)
        if (s.moon > 0) drawRect(moonGlow, alpha = s.moon)
    }

    private val landscape: ImageBitmap = render {
        for (r in ridges) {
            drawPath(r.path, r.brush)
            for (tree in r.trees) {
                drawOval(SHADOW, topLeft = tree.shadowTopLeft, size = tree.shadowSize)
                tree.trunk?.let { drawPath(it, tree.trunkColor) }
                drawPath(tree.crown, tree.crownBrush)
                drawPath(tree.crown, tree.shadeBrush)
            }
            r.mistBrush?.let { drawRect(it, topLeft = Offset(0f, r.mistTop), size = Size(w, h - r.mistTop)) }
        }
    }

    private fun render(block: DrawScope.() -> Unit): ImageBitmap {
        val image = ImageBitmap(w.toInt().coerceAtLeast(1), h.toInt().coerceAtLeast(1))
        CanvasDrawScope().draw(density, direction, Canvas(image), size, block)
        return image
    }

    fun draw(d: DrawScope, t: Float) = with(d) {
        drawImage(background)

        for (i in 0 until stars.size / 5) {
            val a = 0.3f + 0.7f * (0.5f + 0.5f * sin(t * stars[i * 5 + 4] + stars[i * 5 + 3]))
            drawCircle(STAR, radius = stars[i * 5 + 2] * dropScale, center = Offset(stars[i * 5], stars[i * 5 + 1]), alpha = a)
        }

        if (s.sun > 0) {
            if (s.rays > 0) rotate(t * 0.6f, sunCenter) {
                for (i in 0 until 14) rotate(i * 360f / 14, sunCenter) { drawPath(rayPath, rayBrush) }
            }
            drawCircle(sunCore, radius = sunR * 1.4f, center = sunCenter)
        }
        if (s.moon > 0) drawPath(moonPath, MOON, alpha = s.moon)

        for (c in clouds) {
            val cw = c.image.width.toFloat()
            val x = (c.x + t * c.speed) % (w + cw) - cw * 0.5f
            drawImage(c.image, topLeft = Offset(x, c.y), alpha = c.alpha)
        }

        if (birds.isNotEmpty()) {
            val bx = (t * 18f * dropScale) % (w * 1.6f) - w * 0.3f
            val by = h * 0.3f
            for (i in 0 until birds.size / 4) {
                val x = bx + birds[i * 4] * dropScale
                val y = by + birds[i * 4 + 1] * dropScale + sin(t * 0.8f + birds[i * 4 + 2]) * 4 * dropScale
                val f = sin(t * 7 + birds[i * 4 + 2]) * 3 * birds[i * 4 + 3] * dropScale
                val sz = 6 * birds[i * 4 + 3] * dropScale
                birdPath.reset()
                birdPath.moveTo(x - sz, y - f)
                birdPath.quadraticTo(x - sz * 0.4f, y - 2 * dropScale, x, y)
                birdPath.quadraticTo(x + sz * 0.4f, y - 2 * dropScale, x + sz, y - f)
                drawPath(birdPath, BIRD, style = BIRD_STROKE)
            }
        }

        drawImage(landscape)

        for ((i, band) in fogBands.withIndex()) {
            val off = sin(t * 0.06f + i * 1.3f) * w * 0.1f
            translate(off) { drawRect(band.second, topLeft = Offset(-w * 0.15f, band.first - h * 0.1f), size = Size(w * 1.3f, h * 0.2f)) }
        }

        val rainCount = drops.size / 5 - snowCount
        for (i in 0 until drops.size / 5) {
            val z = drops[i * 5 + 4]
            if (i < rainCount) {
                val y = (drops[i * 5 + 1] + t * drops[i * 5 + 3] * (0.6f + z * 0.6f) * dropScale) % (h + 50) - 25
                val x = drops[i * 5] - y * 0.2f - w * 0.15f
                val l = drops[i * 5 + 2] * dropScale
                drawLine(RAIN.copy(alpha = 0.12f + z * 0.3f), Offset(x, y), Offset(x - l * 0.2f, y + l * (0.6f + z * 0.5f)), strokeWidth = (0.5f + z) * dropScale, cap = StrokeCap.Round)
            } else {
                val y = (drops[i * 5 + 1] + t * drops[i * 5 + 3] * 0.06f * (0.5f + z) * dropScale) % (h + 20) - 10
                val x = (drops[i * 5] + sin(t * 0.7f + drops[i * 5 + 2]) * 12 * dropScale) % w
                drawCircle(SNOWFLAKE, radius = (1f + z * 2.2f) * dropScale, center = Offset(x, y), alpha = 0.5f + z * 0.5f)
            }
        }

        if (s.lightning) {
            if (t > nextFlash) {
                flashAt = t
                nextFlash = t + 4 + rnd.nextFloat() * 6
                buildBolt()
            }
            val since = t - flashAt
            if (since < 0.6f) {
                val a = 0.86f.pow(since * 30)
                drawRect(FLASH, alpha = 0.3f * a)
                drawPath(bolt, BOLT, alpha = a, style = BOLT_STROKE)
            }
        }

        drawRect(vignette)
    }

    private fun buildBolt() {
        bolt.reset()
        var x = w * (0.25f + rnd.nextFloat() * 0.5f)
        var y = h * 0.08f
        bolt.moveTo(x, y)
        while (y < h * 0.6f) {
            x += (rnd.nextFloat() - 0.5f) * w * 0.05f
            y += h * (0.03f + rnd.nextFloat() * 0.04f)
            bolt.lineTo(x, y)
        }
    }

    private companion object {
        /** Foliage families blended into each layer's colour: olive, deep green, grey-green. */
        val foliage = listOf(Color(0xFF606E46), Color(0xFF34523A), Color(0xFF6E7868))
        val STAR = Color(0xFFE8EEFF)
        val MOON = Color(0xFFEEF2FA)
        val BIRD = Color(0xBF1E242C)
        val SHADOW = Color(0x29000000)
        val RAIN = Color(0xFFD2DEEC)
        val SNOWFLAKE = Color(0xFFF6F8FA)
        val FLASH = Color(0xFFD6E0FF)
        val BOLT = Color(0xFFF6F8FF)
        val BIRD_STROKE = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.2f, cap = StrokeCap.Round)
        val BOLT_STROKE = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
    }
}

/** Smooth 1D value noise for natural ridgelines, seeded so a scene looks the same each time. */
private class Noise(rnd: Random, private val offset: Int) {
    private val points = FloatArray(64) { rnd.nextFloat() }

    operator fun invoke(x: Float): Float {
        val i = floor(x).toInt()
        val f = x - i
        val a = points[(i + offset) and 63]
        val b = points[(i + 1 + offset) and 63]
        val s = f * f * (3 - 2 * f)
        return a + (b - a) * s
    }

    fun fbm(x: Float) = this(x) * 0.55f + this(x * 2.1f + 7) * 0.28f + this(x * 4.3f + 13) * 0.17f
}
