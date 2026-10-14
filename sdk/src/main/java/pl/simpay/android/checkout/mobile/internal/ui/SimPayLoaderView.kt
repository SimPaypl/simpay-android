package pl.simpay.android.checkout.mobile.internal.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.Interpolator
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.max

/**
 * Branded loading indicator of the sheet.
 *
 * A green arc grows, shrinks and spins around the SimPay mark, whose three bars float
 * with a small stagger. It replaces the platform [android.widget.ProgressBar] so the
 * waiting states look the same as on the SimPay web gateway.
 *
 * Drawn on the canvas rather than declared as an animated vector because the arc mixes
 * two animations of different durations (rotation and dash length), which a single
 * `AnimatedVectorDrawable` cannot express without duplicating every keyframe.
 */
internal class SimPayLoaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private companion object {
        /** Coordinate space of the source artwork; every dimension below is in it. */
        const val VIEWPORT = 115f
        const val CENTER = 57.5f
        const val RADIUS = 50f
        const val STROKE = 5f

        /** Circumference of the arc, the length the dash keyframes are expressed in. */
        const val CIRCUMFERENCE = 2f * Math.PI.toFloat() * RADIUS

        const val SPIN_DURATION_MS = 2000L
        const val DASH_DURATION_MS = 1500L
        const val FLOAT_DURATION_MS = 1200L
        const val ENTRANCE_DURATION_MS = 800L

        /** Final scale of the mark, matching the artwork's entrance animation. */
        const val LOGO_SCALE = 1.2f
        const val FLOAT_TRAVEL = 4f

        /** Stagger of the three bars, as a fraction of the float cycle. */
        val BAR_DELAYS_MS = longArrayOf(200L, 300L, 400L)
    }

    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = SheetStyle.ACCENT
        alpha = (255 * 0.8f).toInt()
    }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = SheetStyle.ACCENT
        alpha = (255 * 0.9f).toInt()
    }

    /** The three bars of the SimPay mark, in artwork coordinates. */
    private val bars: List<Path> = listOf(
        Path().apply {
            moveTo(74.8f, 48.74f)
            lineTo(40.18f, 48.74f)
            rCubicTo(0f, -5.09f, 4.37f, -9.14f, 9.87f, -9.14f)
            lineTo(74.8f, 39.6f)
            lineTo(74.8f, 48.74f)
            close()
        },
        Path().apply {
            moveTo(74.8f, 50.82f)
            lineTo(40.18f, 50.82f)
            lineTo(40.18f, 60f)
            lineTo(74.8f, 60f)
            close()
        },
        Path().apply {
            moveTo(64.93f, 71.07f)
            lineTo(40.18f, 71.07f)
            lineTo(40.18f, 61.92f)
            lineTo(74.8f, 61.92f)
            rCubicTo(0f, 5.1f, -4.37f, 9.15f, -9.87f, 9.15f)
            close()
        }
    )

    private val arcBounds = RectF(
        CENTER - RADIUS,
        CENTER - RADIUS,
        CENTER + RADIUS,
        CENTER + RADIUS
    )

    /** `cubic-bezier(0.16, 1, 0.3, 1)` of the source artwork. */
    private val entranceInterpolator: Interpolator =
        android.view.animation.PathInterpolator(0.16f, 1f, 0.3f, 1f)

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SPIN_DURATION_MS
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { invalidate() }
    }

    private var startedAt = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.elapsedRealtime()
        ticker.start()
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Square by default, so the arc is never squashed by a stretched parent.
        val preferred = context.dp(64)
        val width = resolveSize(preferred, widthMeasureSpec)
        val height = resolveSize(preferred, heightMeasureSpec)
        val side = max(1, minOf(width, height))
        setMeasuredDimension(side, side)
    }

    override fun onDraw(canvas: Canvas) {
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val scale = minOf(width, height) / VIEWPORT

        canvas.save()
        canvas.scale(scale, scale)
        arcPaint.strokeWidth = STROKE

        drawArc(canvas, elapsed)
        drawLogo(canvas, elapsed)

        canvas.restore()
    }

    /**
     * The arc reproduces the SVG dash keyframes: the visible segment grows from a dot to
     * roughly half the circle and back, while its start point travels a full turn. The
     * spin is added on top, which is what makes the movement never look repetitive.
     */
    private fun drawArc(canvas: Canvas, elapsed: Long) {
        val dashPhase = (elapsed % DASH_DURATION_MS) / DASH_DURATION_MS.toFloat()
        val half = if (dashPhase < 0.5f) dashPhase * 2f else (dashPhase - 0.5f) * 2f
        val eased = easeInOut(half)

        val length: Float
        val offset: Float
        if (dashPhase < 0.5f) {
            length = 1f + 149f * eased
            offset = -65f * eased
        } else {
            length = 150f - 149f * eased
            offset = -65f - 249f * eased
        }

        val spin = (elapsed % SPIN_DURATION_MS) / SPIN_DURATION_MS.toFloat() * 360f
        val start = spin + (-offset / CIRCUMFERENCE) * 360f
        val sweep = (length / CIRCUMFERENCE) * 360f

        canvas.drawArc(arcBounds, start, sweep, false, arcPaint)
    }

    private fun drawLogo(canvas: Canvas, elapsed: Long) {
        val entrance = entranceInterpolator.getInterpolation(
            (elapsed.toFloat() / ENTRANCE_DURATION_MS).coerceIn(0f, 1f)
        )
        if (entrance <= 0f) return

        canvas.save()
        canvas.translate(CENTER, CENTER)
        canvas.scale(LOGO_SCALE * entrance, LOGO_SCALE * entrance)
        canvas.translate(-CENTER, -CENTER)

        barPaint.alpha = (255 * 0.9f * entrance).toInt()
        bars.forEachIndexed { index, bar ->
            canvas.save()
            canvas.translate(0f, floatOffset(elapsed, BAR_DELAYS_MS[index]))
            canvas.drawPath(bar, barPaint)
            canvas.restore()
        }

        canvas.restore()
    }

    /** Sine based travel, matching the eased 0 → -4 → 0 keyframes of the artwork. */
    private fun floatOffset(elapsed: Long, delayMs: Long): Float {
        val shifted = elapsed - delayMs
        if (shifted <= 0L) return 0f
        val phase = (shifted % FLOAT_DURATION_MS) / FLOAT_DURATION_MS.toFloat()
        return -FLOAT_TRAVEL * (1f - cos(2f * Math.PI.toFloat() * phase)) / 2f
    }

    private fun easeInOut(fraction: Float): Float =
        (1f - cos(Math.PI.toFloat() * fraction)) / 2f
}

