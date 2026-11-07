package ru.susu.calendarsync

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.sin

/**
 * Полоса прогресса идущей пары в стиле световой панели PS5: заполненная часть мягко «дышит»
 * свечением, по ней слева направо пробегает световая волна, край светится.
 * При animated = false — обычная статичная полоса.
 */
@SuppressLint("ViewConstructor")
class GlowProgress(context: Context, private val barColor: Int, private val trackColor: Int, private val animated: Boolean) : View(context) {
    /** Доля прошедшего времени, 0..1. */
    var progress = 0f
        set(v) {
            field = v.coerceIn(0f, 1f)
            invalidate()
        }

    private val thickness = context.dp(6).toFloat()
    private val glowRadius = context.dp(6).toFloat()
    private val rect = RectF()
    private var phase = 0f

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = trackColor }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = barColor
        maskFilter = BlurMaskFilter(glowRadius, BlurMaskFilter.Blur.NORMAL)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.blendARGB(barColor, Color.WHITE, 0.6f)
        maskFilter = BlurMaskFilter(glowRadius * 0.8f, BlurMaskFilter.Blur.NORMAL)
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        // BlurMaskFilter работает только в программном слое.
        if (animated) setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Запас по высоте под свечение.
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize((thickness + glowRadius * 2).toInt(), heightMeasureSpec),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (animated) animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val r = thickness / 2
        val top = (height - thickness) / 2
        rect.set(0f, top, width.toFloat(), top + thickness)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val fillEnd = width * progress
        if (fillEnd <= 0f) return
        rect.set(0f, top, fillEnd.coerceAtLeast(thickness), top + thickness)
        if (!animated) {
            canvas.drawRoundRect(rect, r, r, fillPaint)
            return
        }

        // «Дыхание» свечения — медленное (период 6 с) и неглубокое, чтобы не отвлекало.
        val t = (android.os.SystemClock.uptimeMillis() % BREATH_MS) / BREATH_MS.toFloat()
        val breath = (sin(t * 2 * Math.PI.toFloat()) + 1) / 2
        glowPaint.alpha = (70 + breath * 70).toInt()
        canvas.drawRoundRect(rect, r, r, glowPaint)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Световая волна слева направо по заполненной части.
        val len = maxOf(fillEnd * 0.35f, thickness * 4)
        val center = -len + phase * (fillEnd + 2 * len)
        val light = ColorUtils.blendARGB(barColor, Color.WHITE, 0.7f)
        wavePaint.shader = LinearGradient(
            center - len, 0f, center + len, 0f,
            intArrayOf(Color.TRANSPARENT, light, Color.TRANSPARENT), floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRoundRect(rect, r, r, wavePaint)

        // Светящийся край — «где мы сейчас».
        headPaint.alpha = (170 + breath * 60).toInt()
        canvas.drawCircle(rect.right - r, top + r, r * 1.4f, headPaint)
    }

    private companion object {
        const val BREATH_MS = 6000L
    }
}
