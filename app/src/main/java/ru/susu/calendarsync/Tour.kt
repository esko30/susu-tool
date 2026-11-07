package ru.susu.calendarsync

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.google.android.material.card.MaterialCardView

/** Шаг тура: что подсветить (null — без подсветки, подсказка по центру) и что сказать. */
data class TourStep(val target: () -> View?, val title: String, val text: String)

/**
 * Тур по приложению: экран затемняется, нужный элемент подсвечивается «окошком» с рамкой,
 * рядом — карточка с подсказкой. Окошко плавно переезжает между шагами.
 */
@SuppressLint("ViewConstructor")
class TourOverlay(context: Context, private val steps: List<TourStep>, private val onEnd: () -> Unit) : FrameLayout(context) {
    private val dim = Paint().apply { color = Color.argb(185, 0, 0, 0) }
    private val clear = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        color = context.color(com.google.android.material.R.attr.colorPrimary)
    }
    private val hole = RectF()
    private var hasHole = false
    private var index = -1
    private val radius = context.dp(14).toFloat()
    private val card: MaterialCardView
    private val titleView = context.text("", 18f, bold = true)
    private val textView = context.secondary("", 15f).apply { setLineSpacing(0f, 1.15f) }
    private val counter = context.secondary("", 12f)
    private val nextButton = context.button("Далее") { next() }

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        val inner = context.vertical(18)
        inner.addView(counter)
        inner.addView(titleView.apply { setPadding(0, context.dp(4), 0, context.dp(6)) })
        inner.addView(textView)
        val row = context.horizontal().apply { setPadding(0, context.dp(12), 0, 0) }
        row.addView(context.textButton("Пропустить") { end() }.weight())
        row.addView(nextButton.weight())
        inner.addView(row)
        card = MaterialCardView(context).apply {
            radius = context.dp(20).toFloat()
            cardElevation = context.dp(8).toFloat()
            setCardBackgroundColor(context.color(com.google.android.material.R.attr.colorSurfaceContainerHigh))
            addView(inner)
        }
        addView(card, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(context.dp(20), 0, context.dp(20), 0)
        })
        alpha = 0f
        post {
            animate().alpha(1f).setDuration(250).start()
            next()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Касание вне карточки — следующий шаг.
        if (event.actionMasked == MotionEvent.ACTION_UP) next()
        return true
    }

    private fun next() {
        index++
        if (index >= steps.size) { end(); return }
        val step = steps[index]
        val target = step.target()?.takeIf { it.isShown && it.width > 0 }
        val to = RectF()
        if (target != null) {
            val loc = IntArray(2)
            val me = IntArray(2)
            target.getLocationInWindow(loc)
            getLocationInWindow(me)
            val pad = context.dp(6)
            to.set(
                (loc[0] - me[0] - pad).toFloat(), (loc[1] - me[1] - pad).toFloat(),
                (loc[0] - me[0] + target.width + pad).toFloat(), (loc[1] - me[1] + target.height + pad).toFloat(),
            )
        }
        // Без цели окошко схлопывается в точку на месте прежнего; новое — раскрывается из центра цели.
        if (target == null) to.set(hole.centerX(), hole.centerY(), hole.centerX(), hole.centerY())
        val from = RectF(if (hasHole) hole else to)
        if (!hasHole) from.inset(to.width() / 2, to.height() / 2)
        hasHole = target != null
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedFraction
                hole.set(
                    from.left + (to.left - from.left) * f, from.top + (to.top - from.top) * f,
                    from.right + (to.right - from.right) * f, from.bottom + (to.bottom - from.bottom) * f,
                )
                invalidate()
            }
            start()
        }

        // Текст карточки — с лёгким проявлением; карточка под окошком или над ним.
        card.animate().alpha(0f).setDuration(120).withEndAction {
            counter.text = "${index + 1} из ${steps.size}"
            titleView.text = step.title
            textView.text = step.text
            nextButton.text = if (index == steps.lastIndex) "Готово" else "Далее"
            card.post {
                val lp = card.layoutParams as LayoutParams
                val gap = context.dp(16)
                if (target == null) {
                    lp.gravity = Gravity.CENTER_VERTICAL; lp.topMargin = 0
                } else if (to.centerY() < height / 2f) {
                    lp.gravity = Gravity.TOP; lp.topMargin = (to.bottom + gap).toInt()
                } else {
                    lp.gravity = Gravity.TOP; lp.topMargin = (to.top - gap - card.height).toInt().coerceAtLeast(gap)
                }
                card.layoutParams = lp
                card.animate().alpha(1f).setDuration(200).start()
            }
        }.start()
    }

    private fun end() {
        animate().alpha(0f).setDuration(200).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onEnd()
        }.start()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dim)
        if (hole.width() > 1) {
            canvas.drawRoundRect(hole, radius, radius, clear)
            canvas.drawRoundRect(hole, radius, radius, ring)
        }
        super.dispatchDraw(canvas)
    }
}
