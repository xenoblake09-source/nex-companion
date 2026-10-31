package com.nexora.robot.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.provider.Settings
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import com.nexora.robot.animation.AnimationCatalog
import com.nexora.robot.animation.AnimationCue
import com.nexora.robot.animation.AnimationPalette
import com.nexora.robot.animation.AnimationSpec
import com.nexora.robot.animation.MotionProfile
import com.nexora.robot.animation.ResolvedAnimation
import com.nexora.robot.util.NexLog
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floorMod
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * NEX's face, and the renderer for all 56 curated screen-only animations.
 *
 * Design rules honoured here:
 *  * **screen only** — this view never touches audio, vibration or notifications;
 *  * **no speaking mouth** — the face has eyes only, so there is nothing to lip-sync;
 *  * **no strobe** — the single flash animation ([MotionProfile.WASH] variant 2) rises and
 *    fades exactly once per play, and [AnimationSpec.flashCount] is capped at 1;
 *  * **reduced motion** — when the user asks for it (in NEX, or via the system
 *    "Remove animations" setting) every animation runs slower, moves less, and looping
 *    flashes stop looping.
 *
 * Timing is driven by [Choreographer] rather than `ValueAnimator` so a system animator scale
 * of zero still leaves a correct, calm final frame on screen instead of a blank view.
 */
class NexAnimationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private data class Palette(val primary: Int, val secondary: Int, val accent: Int)

    private class Geometry(val cx: Float, val cy: Float, val radius: Float, val w: Float, val h: Float)

    private var resolved: ResolvedAnimation =
        AnimationCatalog.resolve(AnimationCatalog.byId(1), reducedMotion = false)

    private var progress: Float = 0f
    private var startedAtMs: Long = 0L
    private var running: Boolean = false
    private var reducedMotion: Boolean = isSystemReducedMotion(context)

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val rect = RectF()
    private val path = Path()

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val duration = resolved.durationMs.coerceAtLeast(1L)
            val elapsed = SystemClock.uptimeMillis() - startedAtMs
            progress = if (resolved.loop) {
                (floorMod(elapsed, duration).toFloat() / duration.toFloat())
            } else {
                (elapsed.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            }
            invalidate()
            if (resolved.loop || elapsed < duration) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
            }
        }
    }

    init {
        // Nothing is clickable here: the view is a status surface, not a control.
        isClickable = false
        isFocusable = false
    }

    /** The animation currently on screen. Useful for QA and for the animation gallery. */
    val currentSpec: AnimationSpec get() = resolved.spec

    fun play(cue: AnimationCue) = playId(cue.animationId)

    fun playId(id: Int) {
        val spec = AnimationCatalog.byId(id)
        if (spec.id == resolved.spec.id && running) return
        apply(spec)
    }

    fun playSpec(spec: AnimationSpec) {
        if (spec.id == resolved.spec.id && running) return
        apply(spec)
    }

    /** Honour (or override) the reduced-motion preference at runtime. */
    fun setReducedMotion(enabled: Boolean) {
        if (reducedMotion == enabled) return
        reducedMotion = enabled
        apply(resolved.spec)
        NexLog.i("reduced motion ${if (enabled) "on" else "off"}")
    }

    private fun apply(spec: AnimationSpec) {
        resolved = AnimationCatalog.resolve(spec, reducedMotion)
        progress = 0f
        startedAtMs = SystemClock.uptimeMillis()
        restart()
    }

    private fun restart() {
        running = true
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (isAttachedToWindow && visibility == VISIBLE) {
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (running) Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        if (isVisible && running) {
            startedAtMs = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback(frameCallback)
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        val spec = resolved.spec
        val palette = paletteFor(spec.palette)
        val radius = min(width, height) * 0.5f * FACE_SCALE
        val geometry = Geometry(width * 0.5f, height * 0.5f, radius, width.toFloat(), height.toFloat())
        val t = progress
        val amp = resolved.amplitude

        // The `when` below is exhaustive over MotionProfile: adding a profile without drawing
        // it is a compile error, so the catalogue can never outgrow the renderer.
        when (spec.profile) {
            MotionProfile.BREATHING -> drawBreathing(canvas, geometry, palette, t, amp)
            MotionProfile.BLINK -> drawBlink(canvas, geometry, palette, t, amp, spec.variant)
            MotionProfile.BROW -> drawBrow(canvas, geometry, palette, t, amp)
            MotionProfile.CRESCENT -> drawCrescent(canvas, geometry, palette, t, amp)
            MotionProfile.RADIAL_GLOW -> drawRadialGlow(canvas, geometry, palette, t, amp, spec.variant)
            MotionProfile.PULSE -> drawPulse(canvas, geometry, palette, t, amp, spec.elementCount)
            MotionProfile.SPARKLE -> drawSparkle(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.ORBIT -> drawOrbit(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.DOTS -> drawDots(canvas, geometry, palette, t, amp, spec.elementCount)
            MotionProfile.SHIMMER -> drawShimmer(canvas, geometry, palette, t, amp, spec.variant)
            MotionProfile.HALO -> drawHalo(canvas, geometry, palette, t, amp, spec.variant)
            MotionProfile.TILT -> drawTilt(canvas, geometry, palette, t, amp)
            MotionProfile.RIPPLE -> drawRipple(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.BURST -> drawBurst(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.GRID -> drawGrid(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.PARTICLES -> drawParticles(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.SYMBOLS -> drawSymbols(canvas, geometry, palette, t, amp, spec.elementCount)
            MotionProfile.TRAIL -> drawTrail(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.WAVE -> drawWave(canvas, geometry, palette, t, amp, spec.elementCount, spec.variant)
            MotionProfile.SCAN -> drawScan(canvas, geometry, palette, t, amp, spec.variant)
            MotionProfile.FRAME -> drawFrame(canvas, geometry, palette, t, amp)
            MotionProfile.BEACON -> drawBeacon(canvas, geometry, palette, t, amp)
            MotionProfile.BOUNCE -> drawBounce(canvas, geometry, palette, t, amp)
            MotionProfile.WASH -> drawWash(canvas, geometry, palette, t, amp, spec.variant, spec.flashCount)
            MotionProfile.BARS -> drawBars(canvas, geometry, palette, t, amp, spec.elementCount)
            MotionProfile.COUNTDOWN -> drawCountdown(canvas, geometry, palette, t, amp)
            MotionProfile.STAR_TRAIL -> drawStarTrail(canvas, geometry, palette, t, amp, spec.elementCount)
        }
    }

    // ------------------------------------------------------------------
    // Face primitives (eyes only: NEX has no speaking mouth)
    // ------------------------------------------------------------------

    private fun drawFace(
        canvas: Canvas,
        geometry: Geometry,
        palette: Palette,
        scaleX: Float = 1f,
        scaleY: Float = 1f,
        rotation: Float = 0f,
        eyeOpen: Float = 1f,
        offsetY: Float = 0f,
        alpha: Int = 255,
        glow: Float = 0f,
    ) {
        val radius = geometry.radius
        canvas.save()
        canvas.translate(geometry.cx, geometry.cy + offsetY)
        canvas.rotate(rotation)
        canvas.scale(scaleX, scaleY)

        if (glow > 0f) {
            glowPaint.shader = RadialGradient(
                0f, 0f, radius * (1.4f + glow),
                intArrayOf(withAlpha(palette.accent, (110 * glow).roundToInt()), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            glowPaint.alpha = alpha
            canvas.drawCircle(0f, 0f, radius * (1.4f + glow), glowPaint)
        }

        fillPaint.shader = RadialGradient(
            -radius * 0.3f, -radius * 0.4f, radius * 1.6f,
            intArrayOf(palette.secondary, palette.primary),
            null, Shader.TileMode.CLAMP,
        )
        fillPaint.alpha = alpha
        canvas.drawCircle(0f, 0f, radius, fillPaint)

        drawEye(canvas, -radius * 0.34f, -radius * 0.06f, radius * 0.145f, eyeOpen, alpha)
        drawEye(canvas, radius * 0.34f, -radius * 0.06f, radius * 0.145f, eyeOpen, alpha)

        fillPaint.shader = null
        canvas.restore()
    }

    private fun drawEye(canvas: Canvas, x: Float, y: Float, radius: Float, openness: Float, alpha: Int) {
        val open = openness.coerceIn(0f, 1f)
        if (open <= 0.08f) {
            strokePaint.shader = null
            strokePaint.color = EYE_COLOR
            strokePaint.alpha = alpha
            strokePaint.strokeWidth = radius * 0.42f
            strokePaint.pathEffect = null
            canvas.drawLine(x - radius * 0.85f, y, x + radius * 0.85f, y, strokePaint)
            return
        }
        fillPaint.shader = null
        fillPaint.color = EYE_COLOR
        fillPaint.alpha = alpha
        rect.set(x - radius, y - radius * open, x + radius, y + radius * open)
        canvas.drawOval(rect, fillPaint)

        fillPaint.color = Color.WHITE
        fillPaint.alpha = (alpha * 0.8f).roundToInt()
        canvas.drawCircle(x - radius * 0.32f, y - radius * 0.42f * open, radius * 0.3f, fillPaint)
    }

    /** Happy upward arcs instead of open eyes. */
    private fun drawHappyEyes(canvas: Canvas, geometry: Geometry, amount: Float, alpha: Int) {
        val radius = geometry.radius
        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.color = EYE_COLOR
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = radius * 0.11f
        val eyeY = geometry.cy - radius * 0.06f
        val lift = radius * 0.16f * amount
        listOf(-1f, 1f).forEach { side ->
            val ex = geometry.cx + side * radius * 0.34f
            path.reset()
            path.moveTo(ex - radius * 0.17f, eyeY + lift * 0.4f)
            path.quadTo(ex, eyeY - lift, ex + radius * 0.17f, eyeY + lift * 0.4f)
            canvas.drawPath(path, strokePaint)
        }
    }

    // ------------------------------------------------------------------
    // Profile renderers
    // ------------------------------------------------------------------

    private fun drawBreathing(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val scale = 1f + sin(t * TAU) * amp * 0.45f
        drawFace(canvas, g, p, scale, 1f / scale.coerceAtLeast(0.6f), glow = amp * 0.5f)
    }

    private fun drawBlink(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, variant: Int) {
        val openness = when (variant) {
            2 -> 1f - max(blinkAt(t, 0.30f, 0.16f), blinkAt(t, 0.62f, 0.16f))
            3 -> 1f - blinkAt(t, 0.55f, 0.42f) * 0.92f
            else -> 1f - blinkAt(t, 0.45f, 0.20f)
        }
        val droop = if (variant == 3) amp * 0.12f * g.radius else 0f
        drawFace(canvas, g, p, eyeOpen = openness, offsetY = droop, glow = amp * 0.2f)
    }

    private fun drawBrow(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val lift = easeInOut(t) * amp * g.radius * 0.30f
        drawFace(canvas, g, p, eyeOpen = 1f, glow = amp * 0.3f)
        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.color = withAlpha(p.accent, 220)
        strokePaint.strokeWidth = g.radius * 0.09f
        val browY = g.cy - g.radius * 0.42f - lift
        listOf(-1f, 1f).forEach { side ->
            val bx = g.cx + side * g.radius * 0.34f
            path.reset()
            path.moveTo(bx - g.radius * 0.2f, browY + g.radius * 0.05f)
            path.quadTo(bx, browY - g.radius * 0.06f, bx + g.radius * 0.2f, browY + g.radius * 0.02f)
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawCrescent(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val amount = easeOut(t)
        val scale = 1f + sin(t * Math.PI.toFloat()) * amp * 0.18f
        canvas.save()
        drawFace(canvas, g, p, scale, scale, glow = amp * 0.7f * amount)
        canvas.restore()
        // Cover the open eyes with the face colour, then draw happy arcs on top.
        fillPaint.shader = null
        fillPaint.color = withAlpha(p.primary, 255)
        val r = g.radius
        listOf(-1f, 1f).forEach { side ->
            val ex = g.cx + side * r * 0.34f * scale
            rect.set(ex - r * 0.2f, g.cy - r * 0.26f, ex + r * 0.2f, g.cy + r * 0.14f)
            canvas.drawOval(rect, fillPaint)
        }
        drawHappyEyes(canvas, g, amount, 255)
    }

    private fun drawRadialGlow(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, variant: Int) {
        val pulse = (sin(t * TAU) + 1f) * 0.5f
        when (variant) {
            2 -> {
                // Rest mode moon: dark sky, crescent moon, slow glow.
                fillPaint.shader = null
                fillPaint.color = NIGHT_SKY
                canvas.drawCircle(g.cx, g.cy, g.radius * 1.25f, fillPaint)
                val moonX = g.cx + g.radius * 0.1f
                val moonY = g.cy - g.radius * 0.05f
                fillPaint.color = withAlpha(p.secondary, (180 + 60 * pulse * amp).roundToInt().coerceAtMost(255))
                canvas.drawCircle(moonX, moonY, g.radius * 0.55f, fillPaint)
                fillPaint.color = NIGHT_SKY
                canvas.drawCircle(moonX + g.radius * 0.24f, moonY - g.radius * 0.12f, g.radius * 0.5f, fillPaint)
                drawStars(canvas, g, count = 7, t = t, amp = amp * 0.5f)
            }
            3 -> {
                // Weather sun: warm disc with slowly rotating rays.
                val rayCount = 12
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.color = withAlpha(p.accent, (120 + 100 * amp * pulse).roundToInt().coerceAtMost(255))
                strokePaint.strokeWidth = g.radius * 0.07f
                val rotation = t * 360f * 0.25f
                for (index in 0 until rayCount) {
                    val angle = Math.toRadians((rotation + index * (360f / rayCount)).toDouble())
                    val inner = g.radius * (0.72f + amp * 0.06f * pulse)
                    val outer = g.radius * (1.02f + amp * 0.16f * pulse)
                    canvas.drawLine(
                        g.cx + (cos(angle) * inner).toFloat(), g.cy + (sin(angle) * inner).toFloat(),
                        g.cx + (cos(angle) * outer).toFloat(), g.cy + (sin(angle) * outer).toFloat(),
                        strokePaint,
                    )
                }
                drawFace(canvas, g, p, glow = amp * (0.5f + 0.5f * pulse))
            }
            4 -> {
                // Privacy shield: outlined shield that breathes.
                drawFace(canvas, g, p, glow = amp * 0.4f * pulse)
                val scale = 1.18f + amp * 0.06f * pulse
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.color = withAlpha(p.accent, (170 + 70 * amp * pulse).roundToInt().coerceAtMost(255))
                strokePaint.strokeWidth = g.radius * 0.08f
                path.reset()
                val top = g.cy - g.radius * scale
                val bottom = g.cy + g.radius * scale * 1.05f
                val halfWidth = g.radius * scale * 0.86f
                path.moveTo(g.cx, top)
                path.lineTo(g.cx + halfWidth, top + g.radius * 0.34f)
                path.lineTo(g.cx + halfWidth * 0.82f, bottom - g.radius * 0.42f)
                path.quadTo(g.cx, bottom + g.radius * 0.1f, g.cx - halfWidth * 0.82f, bottom - g.radius * 0.42f)
                path.lineTo(g.cx - halfWidth, top + g.radius * 0.34f)
                path.close()
                canvas.drawPath(path, strokePaint)
            }
            5 -> {
                // Offline ember: a low, slow warm glow. No flicker fast enough to strobe.
                fillPaint.shader = null
                fillPaint.color = EMBER_BASE
                canvas.drawCircle(g.cx, g.cy, g.radius * 1.2f, fillPaint)
                glowPaint.shader = RadialGradient(
                    g.cx, g.cy + g.radius * 0.35f, g.radius * (0.7f + amp * 0.5f * pulse),
                    intArrayOf(withAlpha(p.primary, (200 * (0.45f + 0.55f * amp * pulse)).roundToInt()), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(g.cx, g.cy + g.radius * 0.35f, g.radius * (0.7f + amp * 0.5f * pulse), glowPaint)
                drawFace(canvas, g, p, eyeOpen = 0.55f + 0.2f * pulse, alpha = 210, glow = amp * 0.25f)
            }
            else -> {
                val glow = amp * (0.35f + 0.65f * pulse)
                drawFace(canvas, g, p, scale = 1f + amp * 0.08f * pulse, glow = glow)
            }
        }
    }

    private fun drawPulse(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, rings: Int) {
        drawFace(canvas, g, p, glow = amp * 0.4f)
        val count = rings.coerceIn(1, 6)
        strokePaint.shader = null
        strokePaint.pathEffect = null
        for (index in 0 until count) {
            val phase = (t + index.toFloat() / count) % 1f
            val radius = g.radius * (1.05f + phase * (0.55f + amp))
            val alpha = ((1f - phase) * 190f * (0.4f + amp)).roundToInt().coerceIn(0, 255)
            strokePaint.color = withAlpha(p.accent, alpha)
            strokePaint.strokeWidth = g.radius * 0.055f * (1f - phase * 0.4f)
            canvas.drawCircle(g.cx, g.cy, radius, strokePaint)
        }
    }

    private fun drawSparkle(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        drawFace(canvas, g, p, glow = amp * 0.5f)
        val total = count.coerceIn(3, 24)
        for (index in 0 until total) {
            val phase = (t + pseudo(index, 11) ) % 1f
            val twinkle = (sin(phase * TAU) + 1f) * 0.5f
            val angle = pseudo(index, 3) * TAU
            val distance = g.radius * (1.15f + pseudo(index, 7) * (0.5f + amp))
            val x = g.cx + cos(angle) * distance
            val y = g.cy + sin(angle) * distance * 0.8f
            val size = g.radius * (0.06f + 0.10f * amp) * (0.4f + twinkle)
            drawFourPointStar(canvas, x, y, size, withAlpha(p.accent, (60 + 190 * twinkle).roundToInt().coerceAtMost(255)))
        }
        if (variant == 2) {
            // Success check: the tick is drawn as the animation progresses.
            strokePaint.shader = null
            strokePaint.pathEffect = null
            strokePaint.color = withAlpha(p.accent, 235)
            strokePaint.strokeWidth = g.radius * 0.13f
            val sweep = easeOut(t)
            path.reset()
            path.moveTo(g.cx - g.radius * 0.42f, g.cy + g.radius * 0.05f)
            val midX = g.cx - g.radius * 0.1f
            val midY = g.cy + g.radius * 0.36f
            path.lineTo(midX, midY)
            path.lineTo(midX + (g.radius * 0.62f) * sweep, midY - (g.radius * 0.72f) * sweep)
            canvas.drawPath(path, strokePaint)
        }
    }

    private fun drawOrbit(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        when (variant) {
            3 -> {
                // Science atom: nucleus plus tilted electron orbits.
                fillPaint.shader = null
                fillPaint.color = withAlpha(p.accent, 235)
                canvas.drawCircle(g.cx, g.cy, g.radius * 0.2f, fillPaint)
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.color = withAlpha(p.secondary, 150)
                strokePaint.strokeWidth = g.radius * 0.035f
                val orbits = count.coerceIn(2, 6)
                for (index in 0 until orbits) {
                    val tilt = index * 60f + t * 40f * amp
                    canvas.save()
                    canvas.rotate(tilt, g.cx, g.cy)
                    rect.set(g.cx - g.radius * 1.05f, g.cy - g.radius * 0.42f, g.cx + g.radius * 1.05f, g.cy + g.radius * 0.42f)
                    canvas.drawOval(rect, strokePaint)
                    canvas.restore()
                    val angle = t * TAU + index * (TAU / orbits)
                    val ex = g.cx + cos(angle) * g.radius * 1.05f
                    val tiltRad = Math.toRadians(tilt.toDouble())
                    val ey = g.cy + sin(angle) * g.radius * 0.42f
                    val rx = g.cx + (ex - g.cx) * cos(tiltRad).toFloat() - (ey - g.cy) * sin(tiltRad).toFloat()
                    val ry = g.cy + (ex - g.cx) * sin(tiltRad).toFloat() + (ey - g.cy) * cos(tiltRad).toFloat()
                    fillPaint.color = withAlpha(p.primary, 235)
                    canvas.drawCircle(rx, ry, g.radius * 0.09f, fillPaint)
                }
            }
            2 -> {
                // Deep thinking: two slow counter-rotating rings of dots.
                drawFace(canvas, g, p, eyeOpen = 0.6f, glow = amp * 0.35f)
                val total = count.coerceIn(3, 8)
                for (ring in 0..1) {
                    val direction = if (ring == 0) 1f else -1f
                    val radius = g.radius * (1.15f + ring * 0.28f)
                    for (index in 0 until total) {
                        val angle = direction * t * TAU * (0.6f + amp * 0.4f) + index * (TAU / total)
                        val x = g.cx + cos(angle) * radius
                        val y = g.cy + sin(angle) * radius * 0.9f
                        fillPaint.shader = null
                        fillPaint.color = withAlpha(if (ring == 0) p.accent else p.secondary, 200)
                        canvas.drawCircle(x, y, g.radius * (0.055f + 0.02f * ring), fillPaint)
                    }
                }
            }
            else -> {
                drawFace(canvas, g, p, eyeOpen = 0.8f, glow = amp * 0.4f)
                val total = count.coerceIn(2, 6)
                for (index in 0 until total) {
                    val angle = t * TAU * (0.8f + amp * 0.5f) + index * (TAU / total)
                    val radius = g.radius * 1.2f
                    val x = g.cx + cos(angle) * radius
                    val y = g.cy + sin(angle) * radius * 0.85f
                    fillPaint.shader = null
                    fillPaint.color = withAlpha(p.accent, 220)
                    canvas.drawCircle(x, y, g.radius * 0.07f, fillPaint)
                    fillPaint.color = withAlpha(p.secondary, 90)
                    canvas.drawCircle(x, y, g.radius * 0.13f, fillPaint)
                }
            }
        }
    }

    private fun drawDots(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int) {
        drawFace(canvas, g, p, eyeOpen = 0.75f, glow = amp * 0.25f)
        val total = count.coerceIn(2, 5)
        val y = g.cy + g.radius * 1.1f
        val spacing = g.radius * 0.42f
        val startX = g.cx - spacing * (total - 1) * 0.5f
        fillPaint.shader = null
        for (index in 0 until total) {
            val phase = (t * total - index).coerceIn(0f, 1f)
            val hop = sin(phase * Math.PI.toFloat()) * g.radius * 0.28f * (0.4f + amp)
            fillPaint.color = withAlpha(p.accent, 230)
            canvas.drawCircle(startX + index * spacing, y - hop, g.radius * 0.09f, fillPaint)
        }
    }

    private fun drawShimmer(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, variant: Int) {
        if (variant == 2) {
            // Hindi / Hinglish hello: the greeting shimmers instead of the face.
            fillPaint.shader = null
            fillPaint.color = withAlpha(p.secondary, 60)
            canvas.drawCircle(g.cx, g.cy, g.radius * 1.15f, fillPaint)
            textPaint.color = withAlpha(p.accent, 235)
            textPaint.textSize = g.radius * 0.62f
            canvas.drawText("नमस्ते", g.cx, g.cy + g.radius * 0.22f, textPaint)
            val bandX = g.cx - g.radius * 1.4f + t * g.radius * 2.8f
            glowPaint.shader = LinearGradient(
                bandX - g.radius * 0.4f, 0f, bandX + g.radius * 0.4f, 0f,
                intArrayOf(Color.TRANSPARENT, withAlpha(Color.WHITE, (150 * amp).roundToInt()), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(g.cx - g.radius * 1.4f, g.cy - g.radius, g.cx + g.radius * 1.4f, g.cy + g.radius, glowPaint)
            return
        }
        drawFace(canvas, g, p, glow = amp * 0.5f)
        val bandX = g.cx - g.radius * 1.6f + t * g.radius * 3.2f
        canvas.save()
        path.reset()
        path.addCircle(g.cx, g.cy, g.radius, Path.Direction.CW)
        canvas.clipPath(path)
        glowPaint.shader = LinearGradient(
            bandX - g.radius * 0.45f, g.cy - g.radius, bandX + g.radius * 0.45f, g.cy + g.radius,
            intArrayOf(Color.TRANSPARENT, withAlpha(Color.WHITE, (170 * amp).roundToInt()), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(g.cx - g.radius, g.cy - g.radius, g.cx + g.radius, g.cy + g.radius, glowPaint)
        canvas.restore()
    }

    private fun drawHalo(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, variant: Int) {
        drawFace(canvas, g, p, glow = amp * 0.45f)
        val radius = g.radius * (1.22f + amp * 0.06f * sin(t * TAU))
        strokePaint.shader = null
        if (variant == 2) {
            strokePaint.pathEffect = DashPathEffect(floatArrayOf(g.radius * 0.22f, g.radius * 0.16f), t * g.radius)
            strokePaint.color = withAlpha(p.accent, 210)
            strokePaint.strokeWidth = g.radius * 0.07f
            canvas.drawCircle(g.cx, g.cy, radius, strokePaint)
            strokePaint.pathEffect = null
            return
        }
        strokePaint.pathEffect = null
        strokePaint.color = withAlpha(p.secondary, 90)
        strokePaint.strokeWidth = g.radius * 0.1f
        canvas.drawCircle(g.cx, g.cy, radius, strokePaint)
        // A brighter travelling arc shows "speaking" without any mouth movement.
        val sweep = 100f + 60f * amp
        strokePaint.color = withAlpha(p.accent, 235)
        rect.set(g.cx - radius, g.cy - radius, g.cx + radius, g.cy + radius)
        canvas.drawArc(rect, t * 360f, sweep, false, strokePaint)
    }

    private fun drawTilt(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val angle = sin(t * TAU) * 9f * (0.4f + amp)
        drawFace(canvas, g, p, rotation = angle, eyeOpen = 0.9f, glow = amp * 0.3f)
    }

    private fun drawRipple(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        when (variant) {
            2 -> {
                // Weather rain: falling drops plus the ripples they make.
                drawFace(canvas, g, p, eyeOpen = 0.85f, glow = amp * 0.25f)
                val drops = count.coerceIn(3, 8)
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.strokeWidth = g.radius * 0.05f
                for (index in 0 until drops) {
                    val phase = (t + pseudo(index, 5)) % 1f
                    val x = g.cx - g.radius + pseudo(index, 13) * g.radius * 2f
                    val y = g.cy - g.radius * 1.3f + phase * g.radius * 2.4f
                    strokePaint.color = withAlpha(p.accent, (200 * (1f - phase)).roundToInt().coerceIn(0, 255))
                    canvas.drawLine(x, y, x - g.radius * 0.05f, y + g.radius * 0.18f, strokePaint)
                    if (phase > 0.82f) {
                        val spread = (phase - 0.82f) / 0.18f
                        strokePaint.color = withAlpha(p.secondary, (160 * (1f - spread)).roundToInt().coerceIn(0, 255))
                        rect.set(
                            x - g.radius * 0.3f * spread, g.cy + g.radius * 1.05f - g.radius * 0.08f * spread,
                            x + g.radius * 0.3f * spread, g.cy + g.radius * 1.05f + g.radius * 0.08f * spread,
                        )
                        canvas.drawOval(rect, strokePaint)
                    }
                }
            }
            3 -> {
                // Microphone off: a muted microphone glyph with slow, quiet ripples.
                drawFace(canvas, g, p, eyeOpen = 0.6f, alpha = 200)
                val micX = g.cx
                val micY = g.cy + g.radius * 1.15f
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.color = withAlpha(p.accent, 200)
                strokePaint.strokeWidth = g.radius * 0.1f
                rect.set(micX - g.radius * 0.12f, micY - g.radius * 0.28f, micX + g.radius * 0.12f, micY + g.radius * 0.1f)
                canvas.drawRoundRect(rect, g.radius * 0.12f, g.radius * 0.12f, strokePaint)
                canvas.drawLine(micX, micY + g.radius * 0.1f, micX, micY + g.radius * 0.26f, strokePaint)
                canvas.drawLine(micX - g.radius * 0.3f, micY - g.radius * 0.34f, micX + g.radius * 0.3f, micY + g.radius * 0.3f, strokePaint)
                for (index in 0 until count.coerceIn(1, 4)) {
                    val phase = (t + index * 0.33f) % 1f
                    strokePaint.color = withAlpha(p.secondary, ((1f - phase) * 120f * amp).roundToInt().coerceIn(0, 255))
                    canvas.drawCircle(micX, micY, g.radius * (0.4f + phase * 0.6f), strokePaint)
                }
            }
            else -> {
                // Asking for clarification: soft ripples, no alarming motion.
                drawFace(canvas, g, p, eyeOpen = 0.9f, glow = amp * 0.3f)
                strokePaint.shader = null
                strokePaint.pathEffect = null
                for (index in 0 until count.coerceIn(1, 5)) {
                    val phase = (t + index * 0.25f) % 1f
                    val radius = g.radius * (1.05f + phase * 0.7f)
                    strokePaint.color = withAlpha(p.accent, ((1f - phase) * 150f * (0.5f + amp)).roundToInt().coerceIn(0, 255))
                    strokePaint.strokeWidth = g.radius * 0.05f
                    canvas.drawCircle(g.cx, g.cy, radius, strokePaint)
                }
            }
        }
    }

    private fun drawBurst(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        drawFace(canvas, g, p, scale = 1f + (1f - t) * amp * 0.12f, glow = amp * (1f - t))
        val total = count.coerceIn(6, 32)
        val eased = easeOut(t)
        for (index in 0 until total) {
            val angle = pseudo(index, 17) * TAU
            val distance = g.radius * (0.6f + eased * (1.1f + amp * 0.9f) * (0.6f + pseudo(index, 29) * 0.7f))
            val x = g.cx + cos(angle) * distance
            val y = g.cy + sin(angle) * distance - eased * g.radius * 0.25f
            val alpha = ((1f - t) * 235f).roundToInt().coerceIn(0, 255)
            val size = g.radius * (0.05f + 0.05f * pseudo(index, 31)) * (1f - t * 0.4f)
            fillPaint.shader = null
            fillPaint.color = withAlpha(if (index % 2 == 0) p.accent else p.secondary, alpha)
            canvas.drawCircle(x, y, size, fillPaint)
        }
        if (variant == 2) {
            strokePaint.shader = null
            strokePaint.pathEffect = null
            strokePaint.color = withAlpha(p.accent, ((1f - t) * 200f).roundToInt().coerceIn(0, 255))
            strokePaint.strokeWidth = g.radius * 0.06f
            canvas.drawCircle(g.cx, g.cy, g.radius * (1f + eased * 0.9f), strokePaint)
        }
    }

    private fun drawGrid(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        if (variant == 2) {
            // Coding pixel stream: columns of small blocks falling at different speeds.
            val columns = 8
            val cell = g.radius * 2f / columns
            fillPaint.shader = null
            for (column in 0 until columns) {
                val speed = 0.6f + pseudo(column, 19) * 1.2f
                val rows = 8
                for (row in 0 until rows) {
                    val phase = (t * speed + row / rows.toFloat() + pseudo(column, 23)) % 1f
                    val x = g.cx - g.radius + column * cell + cell * 0.2f
                    val y = g.cy - g.radius + phase * g.radius * 2f
                    val alpha = ((1f - abs(phase - 0.5f) * 2f) * 230f * (0.4f + amp)).roundToInt().coerceIn(0, 255)
                    fillPaint.color = withAlpha(if (row % 3 == 0) p.accent else p.secondary, alpha)
                    canvas.drawRoundRect(x, y, x + cell * 0.6f, y + cell * 0.42f, cell * 0.1f, cell * 0.1f, fillPaint)
                }
            }
            return
        }
        // Study focus grid: cells light up in a calm wave.
        val side = 4
        val cell = g.radius * 2f / side
        fillPaint.shader = null
        for (row in 0 until side) {
            for (column in 0 until side) {
                val index = row * side + column
                val phase = (t * (0.7f + amp * 0.6f) - index * 0.06f) % 1f
                val lit = (sin(phase * TAU) + 1f) * 0.5f
                val x = g.cx - g.radius + column * cell
                val y = g.cy - g.radius + row * cell
                fillPaint.color = withAlpha(p.secondary, (40 + 90 * lit).roundToInt().coerceIn(0, 255))
                canvas.drawRoundRect(x + cell * 0.08f, y + cell * 0.08f, x + cell * 0.92f, y + cell * 0.92f, cell * 0.18f, cell * 0.18f, fillPaint)
                if (lit > 0.72f) {
                    fillPaint.color = withAlpha(p.accent, ((lit - 0.72f) * 700f).roundToInt().coerceIn(0, 220))
                    canvas.drawRoundRect(x + cell * 0.24f, y + cell * 0.24f, x + cell * 0.76f, y + cell * 0.76f, cell * 0.14f, cell * 0.14f, fillPaint)
                }
            }
        }
        drawFace(canvas, g, p, scale = 0.62f, eyeOpen = 0.8f, glow = amp * 0.3f)
    }

    private fun drawParticles(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        val total = count.coerceIn(4, 24)
        when (variant) {
            2 -> {
                // Memory recall constellation: stable points, faint links, gentle twinkle.
                val points = ArrayList<FloatArray>(total)
                for (index in 0 until total) {
                    val angle = pseudo(index, 37) * TAU
                    val distance = g.radius * (0.35f + pseudo(index, 41) * 0.95f)
                    points.add(floatArrayOf(g.cx + cos(angle) * distance, g.cy + sin(angle) * distance * 0.85f))
                }
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.strokeWidth = g.radius * 0.014f
                for (index in 0 until total - 1) {
                    val a = points[index]
                    val b = points[index + 1]
                    if (hypot(a[0] - b[0], a[1] - b[1]) < g.radius * 0.95f) {
                        strokePaint.color = withAlpha(p.secondary, (60 + 60 * amp * sin(t * TAU + index)).roundToInt().coerceIn(0, 255))
                        canvas.drawLine(a[0], a[1], b[0], b[1], strokePaint)
                    }
                }
                for (index in 0 until total) {
                    val twinkle = (sin(t * TAU + pseudo(index, 43) * TAU) + 1f) * 0.5f
                    fillPaint.shader = null
                    fillPaint.color = withAlpha(p.accent, (110 + 140 * twinkle).roundToInt().coerceAtMost(255))
                    canvas.drawCircle(points[index][0], points[index][1], g.radius * (0.035f + 0.03f * twinkle), fillPaint)
                }
            }
            3 -> {
                // Good night: stars fading out one after another.
                for (index in 0 until total) {
                    val start = index / total.toFloat()
                    val local = ((t - start) + 1f) % 1f
                    val alpha = ((1f - local) * 230f).roundToInt().coerceIn(0, 255)
                    val x = g.cx - g.radius * 1.2f + pseudo(index, 47) * g.radius * 2.4f
                    val y = g.cy - g.radius * 1.1f + pseudo(index, 53) * g.radius * 2.2f
                    drawFourPointStar(canvas, x, y, g.radius * (0.05f + 0.05f * amp), withAlpha(p.accent, alpha))
                }
                drawFace(canvas, g, p, eyeOpen = 0.25f, alpha = 215, glow = amp * 0.2f)
            }
            else -> {
                // Book learning: motes drifting upward with a gentle sway.
                drawFace(canvas, g, p, eyeOpen = 0.85f, glow = amp * 0.3f)
                fillPaint.shader = null
                for (index in 0 until total) {
                    val phase = (t + pseudo(index, 59)) % 1f
                    val baseX = g.cx - g.radius * 1.1f + pseudo(index, 61) * g.radius * 2.2f
                    val sway = sin(phase * TAU * (1f + pseudo(index, 67))) * g.radius * 0.16f * (0.4f + amp)
                    val y = g.cy + g.radius * 1.15f - phase * g.radius * 2.3f
                    val alpha = (sin(phase * Math.PI.toFloat()) * 210f).roundToInt().coerceIn(0, 255)
                    fillPaint.color = withAlpha(if (index % 3 == 0) p.accent else p.secondary, alpha)
                    canvas.drawCircle(baseX + sway, y, g.radius * (0.035f + 0.035f * pseudo(index, 71)), fillPaint)
                }
            }
        }
    }

    private fun drawSymbols(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int) {
        drawFace(canvas, g, p, scale = 0.7f, eyeOpen = 0.85f, glow = amp * 0.3f)
        val total = count.coerceIn(3, 10)
        textPaint.textSize = g.radius * 0.34f
        for (index in 0 until total) {
            val phase = (t + pseudo(index, 73)) % 1f
            val x = g.cx - g.radius * 1.15f + pseudo(index, 79) * g.radius * 2.3f
            val y = g.cy + g.radius * 1.2f - phase * g.radius * 2.4f
            val alpha = (sin(phase * Math.PI.toFloat()) * 220f).roundToInt().coerceIn(0, 255)
            textPaint.color = withAlpha(if (index % 2 == 0) p.accent else p.secondary, alpha)
            canvas.save()
            canvas.rotate(sin(phase * TAU) * 12f * amp, x, y)
            canvas.drawText(MATH_GLYPHS[index % MATH_GLYPHS.size], x, y, textPaint)
            canvas.restore()
        }
    }

    private fun drawTrail(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        drawFace(canvas, g, p, glow = amp * 0.35f)
        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.strokeWidth = g.radius * 0.09f
        val samples = (count * 6).coerceIn(12, 90)
        if (variant == 2) {
            // Friendly wave-light: a light arc sweeping from side to side.
            val sweep = sin(t * TAU) * 0.9f
            path.reset()
            for (index in 0..samples) {
                val fraction = index / samples.toFloat()
                val angle = Math.PI.toFloat() * (0.15f + fraction * 0.7f)
                val radius = g.radius * (1.25f + 0.18f * sin(fraction * Math.PI.toFloat() + sweep * 2f))
                val x = g.cx + cos(angle + sweep * 0.35f) * radius
                val y = g.cy - sin(angle + sweep * 0.35f) * radius * 0.7f
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            strokePaint.shader = LinearGradient(
                g.cx - g.radius * 1.4f, 0f, g.cx + g.radius * 1.4f, 0f,
                intArrayOf(withAlpha(p.secondary, 40), withAlpha(p.accent, 235), withAlpha(p.secondary, 40)),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawPath(path, strokePaint)
            strokePaint.shader = null
            return
        }
        // Creativity paint-light trail: a flowing curve with a bright head.
        path.reset()
        var headX = g.cx
        var headY = g.cy
        for (index in 0..samples) {
            val fraction = index / samples.toFloat()
            val phase = (t + fraction * 0.6f) * TAU
            val x = g.cx - g.radius * 1.2f + fraction * g.radius * 2.4f
            val y = g.cy + sin(phase) * g.radius * (0.35f + amp * 0.5f) * sin(fraction * Math.PI.toFloat())
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            headX = x
            headY = y
        }
        strokePaint.shader = LinearGradient(
            g.cx - g.radius * 1.2f, 0f, g.cx + g.radius * 1.2f, 0f,
            intArrayOf(withAlpha(p.secondary, 30), withAlpha(p.accent, 220)),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, strokePaint)
        strokePaint.shader = null
        fillPaint.shader = null
        fillPaint.color = withAlpha(Color.WHITE, 210)
        canvas.drawCircle(headX, headY, g.radius * 0.07f, fillPaint)
    }

    private fun drawWave(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int, variant: Int,
    ) {
        when (variant) {
            2 -> {
                // Network waiting: two scrolling waves.
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.strokeWidth = g.radius * 0.07f
                for (layer in 0..1) {
                    path.reset()
                    val samples = 60
                    for (index in 0..samples) {
                        val fraction = index / samples.toFloat()
                        val x = g.cx - g.radius * 1.3f + fraction * g.radius * 2.6f
                        val y = g.cy + sin((fraction * 3f + t * (1f + layer * 0.4f)) * TAU) *
                            g.radius * (0.18f + 0.14f * layer) * (0.5f + amp)
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    strokePaint.color = withAlpha(if (layer == 0) p.accent else p.secondary, if (layer == 0) 210 else 120)
                    canvas.drawPath(path, strokePaint)
                }
            }
            3 -> {
                // Language switch swirl: a spiral turning once.
                strokePaint.shader = null
                strokePaint.pathEffect = null
                strokePaint.strokeWidth = g.radius * 0.08f
                strokePaint.color = withAlpha(p.accent, 220)
                path.reset()
                val turns = 2.4f
                val samples = 120
                for (index in 0..samples) {
                    val fraction = index / samples.toFloat()
                    val angle = fraction * turns * TAU + t * TAU
                    val radius = g.radius * (0.2f + fraction * 1.05f)
                    val x = g.cx + cos(angle) * radius
                    val y = g.cy + sin(angle) * radius
                    if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                canvas.drawPath(path, strokePaint)
                textPaint.color = withAlpha(p.secondary, 235)
                textPaint.textSize = g.radius * 0.42f
                canvas.drawText("अ", g.cx - g.radius * 0.55f, g.cy + g.radius * 0.15f, textPaint)
                canvas.drawText("A", g.cx + g.radius * 0.55f, g.cy + g.radius * 0.15f, textPaint)
            }
            else -> {
                // Weather cloud drift: three soft clouds crossing slowly.
                fillPaint.shader = null
                val clouds = count.coerceIn(2, 5)
                for (index in 0 until clouds) {
                    val speed = 0.35f + pseudo(index, 83) * 0.5f
                    val span = g.radius * 3.2f
                    val drift = (t * speed + pseudo(index, 89)) % 1f
                    val x = g.cx - span * 0.5f + drift * span
                    val y = g.cy - g.radius * 0.6f + pseudo(index, 97) * g.radius * 1.2f
                    val scale = 0.6f + pseudo(index, 101) * 0.7f
                    fillPaint.color = withAlpha(p.secondary, (120 + 90 * amp).roundToInt().coerceAtMost(255))
                    canvas.drawCircle(x, y, g.radius * 0.34f * scale, fillPaint)
                    canvas.drawCircle(x + g.radius * 0.3f * scale, y + g.radius * 0.06f, g.radius * 0.26f * scale, fillPaint)
                    canvas.drawCircle(x - g.radius * 0.3f * scale, y + g.radius * 0.08f, g.radius * 0.24f * scale, fillPaint)
                }
                drawFace(canvas, g, p, scale = 0.6f, eyeOpen = 0.85f, glow = amp * 0.2f)
            }
        }
    }

    private fun drawScan(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, variant: Int) {
        // A quiet frame plus faint grid, then the sweep.
        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.color = withAlpha(p.secondary, 90)
        strokePaint.strokeWidth = g.radius * 0.03f
        rect.set(g.cx - g.radius * 1.2f, g.cy - g.radius * 1.05f, g.cx + g.radius * 1.2f, g.cy + g.radius * 1.05f)
        canvas.drawRoundRect(rect, g.radius * 0.18f, g.radius * 0.18f, strokePaint)

        val lines = 5
        for (index in 1 until lines) {
            val y = rect.top + rect.height() * index / lines
            strokePaint.color = withAlpha(p.secondary, 40)
            canvas.drawLine(rect.left + g.radius * 0.1f, y, rect.right - g.radius * 0.1f, y, strokePaint)
        }

        glowPaint.shader = if (variant == 2) {
            val x = rect.left + (rect.width() * ((t * 1.4f) % 1f))
            LinearGradient(
                x - g.radius * 0.35f, 0f, x + g.radius * 0.35f, 0f,
                intArrayOf(Color.TRANSPARENT, withAlpha(p.accent, (200 * (0.5f + amp)).roundToInt().coerceAtMost(255)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
        } else {
            val y = rect.top + (rect.height() * ((t * 1.2f) % 1f))
            LinearGradient(
                0f, y - g.radius * 0.3f, 0f, y + g.radius * 0.3f,
                intArrayOf(Color.TRANSPARENT, withAlpha(p.accent, (200 * (0.5f + amp)).roundToInt().coerceAtMost(255)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(rect, glowPaint)
        glowPaint.shader = null

        if (variant == 2) {
            drawFace(canvas, g, p, scale = 0.66f, eyeOpen = 0.7f, glow = amp * 0.3f)
        } else {
            // A magnifier: language neutral, and the localized label lives in the UI, not on canvas.
            strokePaint.color = withAlpha(p.accent, 210)
            strokePaint.strokeWidth = g.radius * 0.06f
            canvas.drawCircle(g.cx, g.cy - g.radius * 0.1f, g.radius * 0.34f, strokePaint)
            canvas.drawLine(
                g.cx + g.radius * 0.24f, g.cy + g.radius * 0.14f,
                g.cx + g.radius * 0.5f, g.cy + g.radius * 0.42f, strokePaint,
            )
        }
    }

    private fun drawFrame(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val breathe = 1f + sin(t * TAU) * 0.035f * (0.4f + amp)
        val halfW = g.radius * 1.25f * breathe
        val halfH = g.radius * 1.05f * breathe
        val arm = g.radius * 0.42f
        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.color = withAlpha(p.accent, (170 + 60 * amp * sin(t * TAU)).roundToInt().coerceIn(0, 255))
        strokePaint.strokeWidth = g.radius * 0.075f
        val corners = listOf(
            floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(-1f, 1f), floatArrayOf(1f, 1f),
        )
        corners.forEach { (sx, sy) ->
            val x = g.cx + sx * halfW
            val y = g.cy + sy * halfH
            canvas.drawLine(x, y, x - sx * arm, y, strokePaint)
            canvas.drawLine(x, y, x, y - sy * arm, strokePaint)
        }
        // A recording dot that breathes slowly: never a strobe.
        fillPaint.shader = null
        fillPaint.color = withAlpha(RECORDING_DOT, (150 + 80 * (sin(t * TAU) + 1f) * 0.5f).roundToInt().coerceAtMost(255))
        canvas.drawCircle(g.cx, g.cy - halfH - g.radius * 0.22f, g.radius * 0.09f, fillPaint)
    }

    private fun drawBeacon(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val height = g.radius * 2.2f * easeOut(t)
        val top = g.cy + g.radius * 1.1f - height
        glowPaint.shader = LinearGradient(
            0f, top, 0f, g.cy + g.radius * 1.1f,
            intArrayOf(withAlpha(p.accent, (230 * (0.4f + amp)).roundToInt().coerceAtMost(255)), withAlpha(p.accent, 0)),
            null, Shader.TileMode.CLAMP,
        )
        rect.set(g.cx - g.radius * 0.22f, top, g.cx + g.radius * 0.22f, g.cy + g.radius * 1.1f)
        canvas.drawRect(rect, glowPaint)
        glowPaint.shader = null

        fillPaint.shader = null
        fillPaint.color = withAlpha(Color.WHITE, (200 * (1f - t * 0.35f)).roundToInt().coerceIn(0, 255))
        canvas.drawCircle(g.cx, top, g.radius * 0.14f * (0.7f + amp * 0.6f), fillPaint)

        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.color = withAlpha(p.secondary, ((1f - t) * 160f).roundToInt().coerceIn(0, 255))
        strokePaint.strokeWidth = g.radius * 0.04f
        canvas.drawCircle(g.cx, g.cy + g.radius * 1.1f, g.radius * (0.3f + t * 0.9f), strokePaint)
    }

    private fun drawBounce(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val hop = abs(sin(t * Math.PI.toFloat())) * g.radius * 0.5f * (0.4f + amp)
        val squash = 1f - (hop / (g.radius * 0.9f)).coerceAtMost(0.35f) * 0.3f
        drawFace(
            canvas, g, p,
            scaleX = 2f - squash, scaleY = squash,
            offsetY = -hop, eyeOpen = 0.95f, glow = amp * 0.5f,
        )
        // A soft shadow that shrinks as NEX rises.
        fillPaint.shader = null
        fillPaint.color = withAlpha(SHADOW, (110 * (1f - hop / (g.radius * 0.9f))).roundToInt().coerceIn(0, 255))
        val shadowWidth = g.radius * (0.85f - 0.25f * (hop / g.radius))
        rect.set(g.cx - shadowWidth, g.cy + g.radius * 0.92f, g.cx + shadowWidth, g.cy + g.radius * 1.06f)
        canvas.drawOval(rect, fillPaint)
    }

    private fun drawWash(
        canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, variant: Int, flashCount: Int,
    ) {
        if (variant == 2) {
            // Weather lightning: exactly one soft flash (flashCount is capped at 1 by the spec).
            val intensity = if (flashCount > 0) sin(min(t, 1f) * Math.PI.toFloat()) else 0f
            fillPaint.shader = null
            fillPaint.color = withAlpha(NIGHT_SKY, 255)
            canvas.drawRect(0f, 0f, g.w, g.h, fillPaint)
            drawStars(canvas, g, count = 6, t = t * 0.2f, amp = amp * 0.4f)
            glowPaint.shader = RadialGradient(
                g.cx, g.cy - g.radius * 0.4f, g.radius * 2.2f,
                intArrayOf(withAlpha(p.accent, (210 * intensity * (0.4f + amp)).roundToInt().coerceAtMost(255)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, g.w, g.h, glowPaint)
            glowPaint.shader = null
            drawFace(canvas, g, p, eyeOpen = 1f - 0.35f * intensity, alpha = 225)
            return
        }
        // Good morning sunrise: the light rises once, gently.
        val rise = easeInOut(t)
        fillPaint.shader = LinearGradient(
            0f, g.h, 0f, 0f,
            intArrayOf(withAlpha(p.primary, 200), withAlpha(p.secondary, (60 + 140 * rise).roundToInt().coerceAtMost(255))),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, g.w, g.h, fillPaint)
        fillPaint.shader = null

        val sunY = g.cy + g.radius * (1.1f - 1.5f * rise)
        glowPaint.shader = RadialGradient(
            g.cx, sunY, g.radius * (0.9f + amp * 0.6f),
            intArrayOf(withAlpha(Color.WHITE, 220), withAlpha(p.accent, 90), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(g.cx, sunY, g.radius * (0.9f + amp * 0.6f), glowPaint)
        glowPaint.shader = null
        fillPaint.color = withAlpha(p.accent, 235)
        canvas.drawCircle(g.cx, sunY, g.radius * 0.34f, fillPaint)
        drawFace(canvas, g, p, eyeOpen = 0.35f + 0.65f * rise, offsetY = -g.radius * 0.1f, alpha = 220)
    }

    private fun drawBars(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int) {
        val bars = count.coerceIn(5, 20)
        val totalWidth = g.radius * 2.1f
        val barWidth = totalWidth / bars * 0.55f
        val gap = totalWidth / bars
        fillPaint.shader = null
        for (index in 0 until bars) {
            val phase = t * TAU + index * 0.55f
            val height = g.radius * (0.18f + (sin(phase) + 1f) * 0.5f * (0.5f + amp) * 1.5f)
            val x = g.cx - totalWidth * 0.5f + index * gap
            val top = g.cy + g.radius * 0.7f - height
            fillPaint.color = withAlpha(if (index % 2 == 0) p.accent else p.secondary, 225)
            canvas.drawRoundRect(x, top, x + barWidth, g.cy + g.radius * 0.7f, barWidth * 0.4f, barWidth * 0.4f, fillPaint)
        }
        // One floating note glyph: music, without pretending to play audio.
        textPaint.color = withAlpha(p.accent, 230)
        textPaint.textSize = g.radius * 0.45f
        val noteY = g.cy - g.radius * (0.55f + 0.2f * sin(t * TAU))
        canvas.drawText("♪", g.cx + sin(t * TAU) * g.radius * 0.25f, noteY, textPaint)
    }

    private fun drawCountdown(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float) {
        val radius = g.radius * 1.15f
        strokePaint.shader = null
        strokePaint.pathEffect = null
        strokePaint.color = withAlpha(p.secondary, 80)
        strokePaint.strokeWidth = g.radius * 0.12f
        canvas.drawCircle(g.cx, g.cy, radius, strokePaint)

        strokePaint.color = withAlpha(p.accent, 235)
        rect.set(g.cx - radius, g.cy - radius, g.cx + radius, g.cy + radius)
        canvas.drawArc(rect, -90f, 360f * (1f - t), false, strokePaint)

        // One calm pulse per cycle, timed to the ring, never a flicker.
        val beat = 1f - min(t * 6f, 1f)
        drawFace(canvas, g, p, scale = 1f + beat * amp * 0.1f, eyeOpen = 0.8f, glow = amp * (0.25f + beat * 0.35f))
    }

    private fun drawStarTrail(canvas: Canvas, g: Geometry, p: Palette, t: Float, amp: Float, count: Int) {
        fillPaint.shader = null
        fillPaint.color = withAlpha(NIGHT_SKY, 255)
        canvas.drawRoundRect(
            g.cx - g.radius * 1.4f, g.cy - g.radius * 1.2f, g.cx + g.radius * 1.4f, g.cy + g.radius * 1.2f,
            g.radius * 0.3f, g.radius * 0.3f, fillPaint,
        )
        val stars = count.coerceIn(5, 20)
        for (index in 0 until stars) {
            val start = index / stars.toFloat()
            val local = ((t - start) + 1f) % 1f
            val angle = Math.PI.toFloat() * (0.15f + local * 0.7f) + pseudo(index, 103) * 0.4f
            val radius = g.radius * (0.5f + pseudo(index, 107) * 0.9f)
            val x = g.cx - cos(angle) * radius * 1.2f
            val y = g.cy + sin(angle) * radius * 0.7f
            val alpha = (sin(local * Math.PI.toFloat()) * 230f).roundToInt().coerceIn(0, 255)
            // A short tail behind each star.
            strokePaint.shader = null
            strokePaint.pathEffect = null
            strokePaint.color = withAlpha(p.secondary, (alpha * 0.45f).roundToInt().coerceIn(0, 255))
            strokePaint.strokeWidth = g.radius * 0.035f
            canvas.drawLine(x + g.radius * 0.22f * (0.4f + amp), y + g.radius * 0.1f, x, y, strokePaint)
            drawFourPointStar(canvas, x, y, g.radius * (0.06f + 0.05f * amp), withAlpha(p.accent, alpha))
        }
        drawFace(canvas, g, p, eyeOpen = 0.4f, alpha = 210, glow = amp * 0.25f)
    }

    private fun drawStars(canvas: Canvas, g: Geometry, count: Int, t: Float, amp: Float) {
        fillPaint.shader = null
        for (index in 0 until count) {
            val twinkle = (sin(t * TAU + pseudo(index, 109) * TAU) + 1f) * 0.5f
            val x = g.cx - g.radius * 1.25f + pseudo(index, 113) * g.radius * 2.5f
            val y = g.cy - g.radius * 1.15f + pseudo(index, 127) * g.radius * 2.3f
            fillPaint.color = withAlpha(Color.WHITE, (70 + 150 * twinkle * (0.4f + amp)).roundToInt().coerceIn(0, 255))
            canvas.drawCircle(x, y, g.radius * (0.025f + 0.02f * twinkle), fillPaint)
        }
    }

    private fun drawFourPointStar(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        if (size <= 0.5f) return
        fillPaint.shader = null
        fillPaint.color = color
        path.reset()
        path.moveTo(x, y - size)
        path.quadTo(x + size * 0.18f, y - size * 0.18f, x + size, y)
        path.quadTo(x + size * 0.18f, y + size * 0.18f, x, y + size)
        path.quadTo(x - size * 0.18f, y + size * 0.18f, x - size, y)
        path.quadTo(x - size * 0.18f, y - size * 0.18f, x, y - size)
        path.close()
        canvas.drawPath(path, fillPaint)
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun paletteFor(palette: AnimationPalette): Palette = when (palette) {
        AnimationPalette.NEUTRAL -> Palette(0xFF8FA9CE.toInt(), 0xFFDDE7F7.toInt(), 0xFFBFD4F2.toInt())
        AnimationPalette.WARM -> Palette(0xFFF6A96B.toInt(), 0xFFFFE0BE.toInt(), 0xFFFFC98A.toInt())
        AnimationPalette.JOY -> Palette(0xFFFFCB5B.toInt(), 0xFFFFEFC2.toInt(), 0xFFFFD98A.toInt())
        AnimationPalette.FOCUS -> Palette(0xFF6F9BF7.toInt(), 0xFFD7E4FF.toInt(), 0xFF9DBBFF.toInt())
        AnimationPalette.NATURE -> Palette(0xFF62C58F.toInt(), 0xFFD6F3E1.toInt(), 0xFF8FDCAF.toInt())
        AnimationPalette.NIGHT -> Palette(0xFF7E8CCB.toInt(), 0xFFC9D0F2.toInt(), 0xFFAEB8EE.toInt())
        AnimationPalette.WATER -> Palette(0xFF57B9DE.toInt(), 0xFFD3EFFA.toInt(), 0xFF86D2EC.toInt())
        AnimationPalette.ALERT -> Palette(0xFFE9A15C.toInt(), 0xFFFFE3C0.toInt(), 0xFFF2B876.toInt())
        AnimationPalette.SUCCESS -> Palette(0xFF5FCB95.toInt(), 0xFFD5F3E3.toInt(), 0xFF8ADCB0.toInt())
        AnimationPalette.PRIVACY -> Palette(0xFF9C86F0.toInt(), 0xFFE4DCFF.toInt(), 0xFFB9A6F7.toInt())
        AnimationPalette.OFFLINE -> Palette(0xFFD2805C.toInt(), 0xFFF0C6AE.toInt(), 0xFFE39A72.toInt())
        AnimationPalette.LANGUAGE -> Palette(0xFF4FBFCF.toInt(), 0xFFD8F3F7.toInt(), 0xFF7FD6E2.toInt())
        AnimationPalette.MEDIA -> Palette(0xFFEC87A9.toInt(), 0xFFFFDEE9.toInt(), 0xFFF7A8C2.toInt())
        AnimationPalette.CELEBRATION -> Palette(0xFFFF8E9B.toInt(), 0xFFFFE6C9.toInt(), 0xFFFFB3A0.toInt())
    }

    private fun blinkAt(t: Float, center: Float, width: Float): Float {
        val distance = abs(t - center) / width
        if (distance >= 1f) return 0f
        return (cos(distance * Math.PI.toFloat()) + 1f) * 0.5f
    }

    private fun easeOut(t: Float): Float = 1f - (1f - t) * (1f - t)

    private fun easeInOut(t: Float): Float = if (t < 0.5f) 2f * t * t else 1f - (-2f * t + 2f) * (-2f * t + 2f) / 2f

    /** Deterministic pseudo-random in 0..1, so a frame never jumps between runs. */
    private fun pseudo(index: Int, salt: Int): Float =
        floorMod(index * 374_761_393 + salt * 668_265_263, 10_007) / 10_007f

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun isSystemReducedMotion(context: Context): Boolean = try {
        Settings.Global.getFloat(
            context.applicationContext.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    } catch (error: Exception) {
        NexLog.w("could not read animator scale", error)
        false
    }

    private companion object {
        const val TAU = (Math.PI * 2).toFloat()
        const val FACE_SCALE = 0.42f
        val EYE_COLOR = 0xFF2B2F45.toInt()
        val NIGHT_SKY = 0xFF22264A.toInt()
        val EMBER_BASE = 0xFF33262A.toInt()
        val SHADOW = 0xFF1B1E2E.toInt()
        val RECORDING_DOT = 0xFFE2705F.toInt()
        val MATH_GLYPHS = listOf("∑", "π", "√", "∞", "+", "−", "×", "÷", "θ", "Δ")
    }
}
