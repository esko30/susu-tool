package ru.susu.calendarsync

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ледяная корочка карточки: непрерывная полоса льда по верхнему краю с плавно меняющейся толщиной
 * и тонкая ледяная кромка. Лежит поверх содержимого, касания пропускает; форма у каждой карточки своя
 * и не меняется.
 */
class Frost(context: Context, private val corner: Float) : View(context) {
    private val d = resources.displayMetrics.density
    private val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    /** Цвет льда: на тёмной теме белый, на светлой — холодный голубоватый, иначе его не видно. */
    private val ice = if (night) Color.WHITE else Color.rgb(140, 178, 218)
    private fun ice(alpha: Int) = Color.argb(alpha, Color.red(ice), Color.green(ice), Color.blue(ice))

    private val clip = Path()
    private val box = RectF()
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crust = Path()
    private val crustLine = Path()
    private val crustPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val crustEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    /** Своего размера у слоя нет: карточка меряется по содержимому, а слой растягивается на неё. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
        setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        box.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.reset()
        clip.addRoundRect(box, corner, corner, Path.Direction.CW)
        // Ледяная кромка: ярче у углов, к середине почти исчезает.
        rim.shader = LinearGradient(0f, 0f, w.toFloat(), 0f,
            intArrayOf(ice(if (night) 90 else 120), ice(20), ice(if (night) 90 else 120)), null, Shader.TileMode.CLAMP)
        rim.strokeWidth = 1.2f * d

        // Корочка: нижний край — плавная волна из двух синусоид, толщина 1,5–4 dp.
        crust.reset()
        crustLine.reset()
        val rc = Random(w * 7 + h * 13)
        val p1 = rc.nextFloat() * 6.28f
        val p2 = rc.nextFloat() * 6.28f
        val f1 = 2 + rc.nextInt(3)
        val f2 = 5 + rc.nextInt(4)
        fun thick(x: Float): Float {
            val u = x / w * 6.2832f
            return (2.6f + 0.9f * sin(u * f1 + p1) + 0.55f * sin(u * f2 + p2)) * d
        }
        val stepX = 3 * d
        crust.moveTo(0f, 0f)
        crust.lineTo(w.toFloat(), 0f)
        var x = w.toFloat()
        while (x >= 0f) { crust.lineTo(x, thick(x)); x -= stepX }
        crust.lineTo(0f, thick(0f))
        crust.close()
        x = 0f
        crustLine.moveTo(0f, thick(0f))
        while (x <= w) { crustLine.lineTo(x, thick(x)); x += stepX }
        crustPaint.shader = LinearGradient(0f, 0f, 0f, 4 * d, ice(if (night) 105 else 140), ice(if (night) 45 else 70), Shader.TileMode.CLAMP)
        crustEdge.strokeWidth = 0.9f * d
        crustEdge.color = ice(if (night) 120 else 150)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawPath(crust, crustPaint)
        canvas.drawPath(crustLine, crustEdge)
        canvas.drawLine(corner * 0.6f, 0.8f * d, width - corner * 0.6f, 0.8f * d, rim)
        canvas.restore()
    }
}
