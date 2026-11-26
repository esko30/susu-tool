package ru.susu.calendarsync

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import com.google.android.material.card.MaterialCardView
import java.time.LocalDate
import java.time.Month
import kotlin.math.sin
import kotlin.random.Random

/**
 * Новогоднее оформление: снег, как в «Погоде» на iPhone.
 *
 * Три слоя глубины: дальние снежинки мелкие, тусклые и медленные, ближние крупные и быстрые; края мягкие,
 * всё слегка сносит ветром. Снег идёт под страницей, а карточки для него — препятствия: снежинки среднего
 * и ближнего слоя садятся на верхний край карточки, едут вместе с ней при прокрутке, сползают по ветру
 * и падают дальше мимо карточки или тают. Дальний слой пролетает позади. Касания проходят сквозь снег.
 */
class SnowView(context: Context) : View(context) {
    private class Flake(
        var x: Float, var y: Float, val size: Float, val speed: Float, val drift: Float,
        val sway: Float, val swaySpeed: Float, val phase: Float, val baseAlpha: Int,
        /** Вероятность сесть на верхний край карточки при пересечении; 0 — всегда пролетает позади. */
        val landChance: Float,
    ) {
        /** Где снежинка нарисована в этом кадре и с какой яркостью — для шапки, которая рисует тот же снег. */
        var drawX = 0f
        var drawAlpha = 0
        /** Карточка, на которой лежит снежинка, и её смещение от левого края карточки; null — падает. */
        var card: View? = null
        var onCardX = 0f
        /** Сколько ещё пролежит, с; доля — для таяния. */
        var life = 0f
        var lifeTotal = 1f
    }

    private class Layer(val count: Int, val size: ClosedFloatingPointRange<Float>, val speed: ClosedFloatingPointRange<Float>,
                        val alpha: IntRange, val landChance: Float)

    private val layers = listOf(
        Layer(160, 1.6f..2.8f, 22f..38f, 70..110, landChance = 0f),     // дальний — позади карточек
        Layer(95, 3.0f..4.6f, 45f..70f, 130..180, landChance = 0.18f),  // средний
        Layer(26, 5.5f..8.5f, 85f..125f, 170..220, landChance = 0.32f), // ближний
    )

    private val flakes = ArrayList<Flake>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    private val sprite: Bitmap = softFlake(if (isNight(context)) Color.WHITE else Color.rgb(140, 166, 196))
    private val density = resources.displayMetrics.density
    /**
     * Ветер, dp/с: плюс — вправо, минус — влево. Раз в 4–9 с выбирается новая цель по силе и направлению,
     * и ветер плавно к ней подтягивается.
     */
    private var wind = 8f
    private var windTarget = 8f
    private var windChangeAt = 0L

    private fun updateWind(now: Long, dt: Float) {
        if (now >= windChangeAt) {
            windTarget = Random.nextFloat() * 44f - 18f // от −18 (влево) до +26 (вправо): чаще вправо, но не всегда
            windChangeAt = now + 4000 + Random.nextLong(5000)
        }
        // Плавное приближение: за ~2 с ветер проходит большую часть пути к цели.
        wind += (windTarget - wind) * (1f - kotlin.math.exp(-dt / 1.6f))
    }
    private var last = 0L

    /** Карточки на экране и их прямоугольники в координатах снега; список карточек обновляется раз в 300 мс. */
    private val cards = ArrayList<View>()
    private val rects = HashMap<View, RectF>()
    private var cardsAt = 0L
    private val own = IntArray(2)
    private val loc = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        flakes.clear()
        if (w == 0 || h == 0) return
        fun ClosedFloatingPointRange<Float>.pick() = start + Random.nextFloat() * (endInclusive - start)
        for (l in layers) repeat(l.count) {
            flakes += Flake(
                x = Random.nextFloat() * w, y = Random.nextFloat() * h, size = l.size.pick() * density,
                speed = l.speed.pick() * density,
                drift = (Random.nextFloat() * 8f - 4f) * density,
                sway = (4f + Random.nextFloat() * 10f) * density,
                swaySpeed = 0.5f + Random.nextFloat() * 0.9f,
                phase = Random.nextFloat() * 6.28f,
                baseAlpha = l.alpha.random(),
                landChance = l.landChance,
            )
        }
    }

    /** Собирает видимые карточки страницы (раз в 300 мс) и их положение (каждый кадр). */
    private fun updateCards(now: Long) {
        val parent = parent as? ViewGroup ?: return
        if (now - cardsAt > 300) {
            cardsAt = now
            cards.clear()
            fun walk(v: View) {
                if (v === this || !v.isShown) return
                if (v is MaterialCardView) { cards += v; return }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            for (i in 0 until parent.childCount) walk(parent.getChildAt(i))
            rects.keys.retainAll(cards.toSet())
        }
        getLocationInWindow(own)
        for (c in cards) {
            c.getLocationInWindow(loc)
            val r = rects.getOrPut(c) { RectF() }
            r.set((loc[0] - own[0]).toFloat(), (loc[1] - own[1]).toFloat(),
                (loc[0] - own[0] + c.width).toFloat(), (loc[1] - own[1] + c.height).toFloat())
        }
    }

    private fun respawn(f: Flake) {
        f.card = null
        f.y = -f.size
        f.x = Random.nextFloat() * width
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val dt = if (last == 0L) 0f else ((now - last).coerceAtMost(100)) / 1000f
        last = now
        val t = now / 1000f
        updateCards(now)
        updateWind(now, dt)
        val windPx = wind * density

        for (f in flakes) {
            val r = f.size / 2
            var alpha = f.baseAlpha
            val on = f.card
            val x: Float
            if (on != null) {
                // Лежит на карточке: едет вместе с ней, медленно сползает по ветру и тает.
                val rect = rects[on]
                if (rect == null || !on.isShown) { respawn(f); continue }
                f.onCardX += (windPx + f.drift) * 0.25f * dt
                f.life -= dt
                x = rect.left + f.onCardX
                f.x = x
                f.y = rect.top - r * 0.7f
                if (x < rect.left - r || x > rect.right + r) {
                    // Сползла с края — падает дальше мимо карточки.
                    f.card = null
                } else if (f.life <= 0f) { respawn(f); continue }
                alpha = (f.baseAlpha * (f.life / f.lifeTotal).coerceIn(0f, 1f)).toInt()
            } else {
                val prevY = f.y
                f.y += f.speed * dt
                // Ближние (крупные) снежинки ветер сносит сильнее, дальние — слабее.
                val depth = (f.size / (5f * density)).coerceIn(0.5f, 1.5f)
                f.x += (windPx * depth + f.drift) * dt
                if (f.y - f.size > height) { respawn(f); continue }
                if (f.x - f.size > width) f.x = -f.size
                else if (f.x + f.size < 0) f.x = width + f.size
                x = f.x + sin(t * f.swaySpeed + f.phase) * f.sway
                if (f.landChance > 0f) {
                    // Пересекла верхний край карточки — садится на него с некоторой вероятностью, иначе пролетает позади.
                    for (c in cards) {
                        val rect = rects[c] ?: continue
                        val edge = rect.top - r * 0.7f
                        if (prevY <= edge && f.y >= edge && x > rect.left + r && x < rect.right - r) {
                            if (Random.nextFloat() > f.landChance) break
                            f.card = c
                            f.onCardX = x - rect.left
                            f.y = edge
                            f.lifeTotal = 2f + Random.nextFloat() * 3f
                            f.life = f.lifeTotal
                            break
                        }
                    }
                }
            }
            f.drawX = x
            f.drawAlpha = alpha
        }
        render(canvas, 0f, 0f)
        // Шапки со снежным фоном перерисовываются вместе со снегом.
        followers.forEach { it.invalidate() }
        if (isShown) postInvalidateOnAnimation()
    }

    /** Рисует снежинки этого кадра, сдвинув их на (dx, dy) — для чужого холста (шапки). */
    private fun render(canvas: Canvas, dx: Float, dy: Float) {
        for (f in flakes) {
            val r = f.size / 2
            dst.set(f.drawX - r - dx, f.y - r - dy, f.drawX + r - dx, f.y + r - dy)
            paint.alpha = f.drawAlpha
            canvas.drawBitmap(sprite, null, dst, paint)
        }
    }

    private val followers = ArrayList<View>()

    /**
     * Непрозрачный фон шапки, на котором идёт тот же снег, что и под страницей: снежинки проходят
     * сквозь шапку без разрыва. Сама шапка остаётся непрозрачной, лента под ней не просвечивает.
     */
    private class Backdrop(private val view: View, private val color: Int) : android.graphics.drawable.Drawable() {
        private val a = IntArray(2)
        private val b = IntArray(2)
        override fun draw(canvas: Canvas) {
            canvas.drawColor(color)
            val snow = active ?: return
            if (!snow.isAttachedToWindow) return
            view.getLocationInWindow(a)
            snow.getLocationInWindow(b)
            canvas.save()
            canvas.clipRect(bounds)
            snow.render(canvas, (a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
            canvas.restore()
        }
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) = Unit
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        active = this
    }

    override fun onDetachedFromWindow() {
        if (active === this) active = null
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        last = 0L
        if (visibility == VISIBLE) postInvalidateOnAnimation()
    }

    companion object {
        private const val TAG = "susu_snow"
        private const val GARLAND_TAG = "susu_garland"

        /** Включено ли новогоднее оформление: карточки по нему добавляют себе заморозку. */
        @Volatile var decorated = false
        private var active: SnowView? = null

        /**
         * Фон шапки цветом [color]; если идёт снег — со снегом. Шапка подписывается на кадры снега,
         * пока она на экране.
         */
        fun backdrop(view: View, color: Int) {
            view.background = Backdrop(view, color)
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { active?.followers?.let { if (v !in it) it += v } }
                override fun onViewDetachedFromWindow(v: View) { active?.followers?.remove(v) }
            })
            if (view.isAttachedToWindow) active?.followers?.let { if (view !in it) it += view }
        }

        private fun isNight(c: Context) =
            (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        /** Пушистая снежинка: плотная середина и мягко растворяющийся край. Рисуется один раз. */
        private fun softFlake(color: Int): Bitmap {
            val size = 48
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = size / 2f
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(c, c, c,
                    intArrayOf(color, color, Color.argb(110, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT),
                    floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP)
            }
            Canvas(bmp).drawCircle(c, c, c, p)
            return bmp
        }

        /** Номер зимы: 2026 для конца 2026 и начала 2027 года (сезон переходит через Новый год). */
        fun winter(d: LocalDate): Int = if (d.monthValue <= 6) d.year - 1 else d.year

        /** Новогодний сезон: с 1 декабря по 14 января. */
        fun season(d: LocalDate): Boolean =
            d.month == Month.DECEMBER || (d.month == Month.JANUARY && d.dayOfMonth <= 14)

        /**
         * Добавить или убрать снег по настройке. Снег — фон под страницами: идёт между карточками,
         * садится на них и скрывается за ними, а не рисуется поверх.
         */
        fun apply(activity: Activity) {
            val host = activity.findViewById<ViewGroup>(R.id.container) ?: return
            val root = host.parent as? ViewGroup ?: return
            val existing = host.findViewWithTag<View>(TAG)
            val garland = root.findViewWithTag<View>(GARLAND_TAG)
            val want = Prefs(activity).snow
            decorated = want
            val d = activity.resources.displayMetrics.density
            if (want && existing == null) {
                host.addView(SnowView(activity).apply { tag = TAG }, 0,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            } else if (!want && existing != null) host.removeView(existing)
            // Гирлянда — отдельная полоса над страницами, поэтому ничего не перекрывает.
            if (want && garland == null) {
                root.addView(Garland(activity).apply { tag = GARLAND_TAG }, 0,
                    android.widget.LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (Garland.HEIGHT_DP * d).toInt()))
            } else if (!want && garland != null) root.removeView(garland)
        }
    }
}
