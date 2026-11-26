package ru.susu.calendarsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.sin

/**
 * Новогодняя гирлянда поверх верха экрана: провод провисает между крючками, на нём разноцветные
 * лампочки, которые мягко светятся и перемигиваются. Касания проходят сквозь неё.
 */
class Garland(context: Context) : View(context) {
    private val d = resources.displayMetrics.density
    private val wire = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.4f * d
        color = Color.argb(200, 70, 80, 70)
    }
    private val socket = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(80, 90, 80) }
    private val bulb = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val pos = FloatArray(2)
    private val tan = FloatArray(2)
    private val oval = RectF()

    private class Bulb(val x: Float, val y: Float, val color: Int, val glow: Bitmap, val speed: Float, val phase: Float)
    private val bulbs = ArrayList<Bulb>()

    private val colors = intArrayOf(
        Color.rgb(255, 72, 72), Color.rgb(255, 196, 40), Color.rgb(60, 205, 110),
        Color.rgb(70, 150, 255), Color.rgb(230, 90, 230),
    )
    private val glows = colors.map { glowBitmap(it) }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        path.reset()
        bulbs.clear()
        if (w == 0) return
        // Провод: дуги между крючками каждые ~110 dp, провисание к середине.
        val span = 110 * d
        val n = maxOf(1, (w / span).toInt())
        val step = w.toFloat() / n
        val top = 1.5f * d
        val sag = 11 * d
        path.moveTo(-2 * d, top)
        for (i in 0 until n) {
            val x0 = i * step
            path.quadTo(x0 + step / 2, top + sag * 2, x0 + step, top)
        }
        // Лампочки равномерно по длине провода.
        val pm = PathMeasure(path, false)
        val gap = 30 * d
        var at = gap / 2
        var k = 0
        while (at < pm.length) {
            pm.getPosTan(at, pos, tan)
            val c = k % colors.size
            bulbs += Bulb(pos[0], pos[1], colors[c], glows[c], 1.2f + (k * 37 % 11) / 10f, k * 1.7f)
            at += gap
            k++
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawPath(path, wire)
        val t = SystemClock.uptimeMillis() / 1000f
        val br = 3.2f * d
        for (b in bulbs) {
            // Мягкое мерцание: у каждой лампочки свой ритм.
            val k = 0.55f + 0.45f * (0.5f + 0.5f * sin(t * b.speed + b.phase))
            // Лампочка плавно покачивается на проводе вокруг цоколя, до ±7°, каждая в своём медленном ритме.
            canvas.save()
            canvas.rotate(7f * sin(t * (0.6f + b.speed * 0.25f) + b.phase * 1.3f), b.x, b.y)
            val g = 13 * d
            oval.set(b.x - g, b.y + br - g, b.x + g, b.y + br + g)
            glowPaint.alpha = (k * 200).toInt()
            canvas.drawBitmap(b.glow, null, oval, glowPaint)
            canvas.drawRect(b.x - 1.6f * d, b.y - 0.5f * d, b.x + 1.6f * d, b.y + 2f * d, socket)
            bulb.color = b.color
            bulb.alpha = (150 + 105 * k).toInt()
            oval.set(b.x - br * 0.8f, b.y + 1.5f * d, b.x + br * 0.8f, b.y + 1.5f * d + br * 2.4f)
            canvas.drawOval(oval, bulb)
            canvas.restore()
        }
        if (isShown) postInvalidateDelayed(33)
    }

    private fun glowBitmap(color: Int): Bitmap {
        val s = 48
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = s / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(c, c, c,
                intArrayOf(Color.argb(170, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP)
        }
        Canvas(bmp).drawCircle(c, c, c, p)
        return bmp
    }

    companion object {
        /** Высота полосы под гирлянду, dp: на столько опускается содержимое экрана. */
        const val HEIGHT_DP = 26
    }
}
