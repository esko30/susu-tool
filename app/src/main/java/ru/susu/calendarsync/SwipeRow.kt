package ru.susu.calendarsync

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Обёртка для карточки, которую можно смахнуть влево или вправо.
 * Вертикальная прокрутка ленты и нажатия на карточку работают как обычно:
 * перехватываем касание, только когда палец явно ушёл вбок.
 */
@SuppressLint("ViewConstructor")
class SwipeRow(context: Context, private val content: View, private val onSwiped: () -> Unit) : FrameLayout(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    init {
        // Отступы карточки переходят на обёртку, чтобы лента выглядела как раньше.
        val m = content.layoutParams as? ViewGroup.MarginLayoutParams
        layoutParams = android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { if (m != null) setMargins(m.leftMargin, m.topMargin, m.rightMargin, m.bottomMargin) }
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!dragging) return ev.actionMasked == MotionEvent.ACTION_DOWN
        val dx = ev.x - downX
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                content.translationX = dx
                content.alpha = 1f - (abs(dx) / width).coerceIn(0f, 0.7f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (ev.actionMasked == MotionEvent.ACTION_UP && abs(dx) > width * 0.33f) {
                    val dir = if (dx > 0) 1 else -1
                    haptic()
                    content.animate().translationX(dir * width.toFloat()).alpha(0f).setDuration(160).withEndAction { onSwiped() }.start()
                } else {
                    content.animate().translationX(0f).alpha(1f).setDuration(180).setInterpolator(DecelerateInterpolator()).start()
                }
            }
        }
        return true
    }

    companion object {
        /** Плавно меняет высоту [view] с текущей на [to] пикселей, потом выполняет [end]. */
        fun collapse(view: View, to: Int, end: () -> Unit) {
            val from = view.height
            if (from <= 0) { end(); return }
            ValueAnimator.ofInt(from, to).apply {
                duration = 200
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    view.layoutParams = view.layoutParams.apply { height = it.animatedValue as Int }
                }
                doOnEnd(end)
            }.start()
        }

        private fun ValueAnimator.doOnEnd(end: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = end()
        })
    }
}
