package ru.susu.calendarsync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import com.google.android.material.progressindicator.CircularProgressIndicator

/** Цвет рейтинга: без баллов — серый, дальше по порогам оценок. */
fun Context.ratingColor(r: Double?) = when {
    r == null || r == 0.0 -> color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    r >= 85 -> Color.rgb(76, 175, 80)
    r >= 75 -> Color.rgb(192, 202, 51)
    r >= 60 -> Color.rgb(255, 152, 0)
    else -> Color.rgb(239, 83, 80)
}

/** Кольцо рейтинга с числом внутри; [set] плавно меняет значение. */
@SuppressLint("ViewConstructor")
class RatingRing(context: Context, private val sizePx: Int) : FrameLayout(context) {
    private val ring = CircularProgressIndicator(context).apply {
        indicatorSize = sizePx
        indicatorInset = 0
        trackThickness = context.dp(if (sizePx > context.dp(80)) 8 else 5)
        trackCornerRadius = context.dp(3)
        max = 1000
        trackColor = context.color(com.google.android.material.R.attr.colorSurfaceContainerHighest)
    }
    private val label = context.text("", if (sizePx > context.dp(80)) 24f else 15f, bold = true).apply { gravity = Gravity.CENTER }

    init {
        addView(ring, LayoutParams(sizePx, sizePx))
        addView(label, LayoutParams(sizePx, sizePx))
    }

    fun set(r: Double?, animate: Boolean = false) {
        val c = context.ratingColor(r)
        ring.setIndicatorColor(c)
        ring.setProgressCompat(((r ?: 0.0).coerceIn(0.0, 100.0) * 10).toInt(), animate)
        label.setTextColor(c)
        label.text = if (r == null) "—" else Grades.fmt(Math.round(r * 10) / 10.0)
    }
}
