package ru.susu.calendarsync

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import com.google.android.material.progressindicator.CircularProgressIndicator

/** Цвет рейтинга: без баллов — серый, дальше по порогам оценок. */
fun Context.ratingColor(r: Double?): Int = when {
    r == null || r == 0.0 -> color(com.google.android.material.R.attr.colorOnSurfaceVariant)
    Forecast.reaches(r, 85.0) -> readable(Color.rgb(76, 175, 80))
    Forecast.reaches(r, 75.0) -> readable(Color.rgb(192, 202, 51))
    Forecast.reaches(r, 60.0) -> readable(Color.rgb(255, 152, 0))
    else -> readable(Color.rgb(239, 83, 80))
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
    private val big = sizePx > context.dp(80)
    private val label = context.text("", if (big) 30f else 19f, bold = true).apply { gravity = Gravity.CENTER }

    init {
        addView(ring, LayoutParams(sizePx, sizePx))
        addView(label, LayoutParams(sizePx, sizePx))
    }

    /** Зачёт: набрано 60 (с округлением в пользу студента) — сразу зелёный, без деления на 4/5; меньше — как обычно. */
    var credit = false

    fun set(r: Double?, animate: Boolean = false) {
        val c = if (credit && r != null && Forecast.reaches(r, 60.0)) context.ratingColor(100.0) else context.ratingColor(r)
        ring.setIndicatorColor(c)
        ring.setProgressCompat(((r ?: 0.0).coerceIn(0.0, 100.0) * 10).toInt(), animate)
        label.setTextColor(c)
        // Рейтинг как отдаёт портал, до сотых: целая часть крупно, дробная мельче, через запятую.
        label.text = if (r == null) "—" else {
            val t = Grades.fmt(Math.round(r * 100) / 100.0).replace('.', ',')
            val comma = t.indexOf(',')
            if (comma < 0) t else android.text.SpannableString(t).apply {
                setSpan(android.text.style.RelativeSizeSpan(0.6f), comma, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
