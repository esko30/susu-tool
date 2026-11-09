package ru.susu.calendarsync

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlin.random.Random

/**
 * Просьба о поддержке: спокойная нижняя панель, не чаще раза в 14 дней.
 * После «Поддержать» открывается СБП; если человек вернулся через 45 секунд – 5 минут,
 * показываем «Спасибо» с конфетти и больше никогда не просим.
 */
object Donate {
    const val URL = "https://finance.ozon.ru/apps/sbp/ozonbankpay/01a1b512-8b85-766c-84dc-2ce3951ada42"

    private const val DAY = 24 * 3600_000L
    const val FIRST_AFTER_DAYS = 14
    const val FIRST_AFTER_LAUNCHES = 15
    const val LATER_DAYS = 14
    const val THANKS_MIN_MS = 45_000L
    const val THANKS_MAX_MS = 5 * 60_000L

    /** Пора ли предложить поддержку (чистая логика, покрыта тестами). */
    fun shouldOffer(now: Long, firstLaunchAt: Long, launches: Int, nextAt: Long, donated: Boolean): Boolean =
        !donated && firstLaunchAt > 0 &&
            now - firstLaunchAt >= FIRST_AFTER_DAYS * DAY &&
            launches >= FIRST_AFTER_LAUNCHES &&
            now >= nextAt

    /** Вернулись из банка за разумное время — похоже, перевод был. */
    fun shouldThank(awayMs: Long) = awayMs in THANKS_MIN_MS..THANKS_MAX_MS

    /** Учёт запусков: вызывается при холодном старте. */
    fun countLaunch(context: Context) {
        val p = Prefs(context)
        if (p.firstLaunchAt == 0L) p.firstLaunchAt = System.currentTimeMillis()
        p.launchCount = p.launchCount + 1
    }

    /** Показать панель, если пора. */
    fun maybeOffer(activity: Activity) {
        val p = Prefs(activity)
        if (!p.hasCredentials || activity.isFinishing) return
        if (!shouldOffer(System.currentTimeMillis(), p.firstLaunchAt, p.launchCount, p.donateNextAt, p.donated)) return
        offer(activity)
    }

    /** Нижняя панель «Нравится SUSU Tool?». */
    fun offer(activity: Activity) {
        val ctx = activity
        val p = Prefs(ctx)
        // Сразу откладываем: закрыли или смахнули панель — это тоже «позже».
        p.donateNextAt = System.currentTimeMillis() + LATER_DAYS * DAY
        val sheet = BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        val row = ctx.horizontal().apply { gravity = Gravity.TOP }
        row.addView(heartTile(ctx, 48, 14))
        row.addView(ctx.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = ctx.dp(14) }
            addView(ctx.text("Нравится SUSU Tool?", 18f, bold = true))
            addView(ctx.secondary("Его делает студент, без рекламы и серверов. Если приложение экономит вам время, " +
                "можно поддержать разработку переводом через СБП.", 14f).apply { setPadding(0, ctx.dp(4), 0, 0) })
        })
        box.addView(row)
        val buttons = ctx.horizontal().apply { setPadding(0, ctx.dp(18), 0, ctx.dp(8)) }
        buttons.addView(ctx.button("Позже", outlined = true) { sheet.dismiss() }.weight())
        buttons.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(10), 1) })
        buttons.addView(ctx.button("Поддержать") { sheet.dismiss(); open(activity) }.weight())
        box.addView(buttons)
        sheet.setContentView(box)
        sheet.show()
    }

    /** Открыть перевод через СБП и запомнить, когда ушли из приложения. */
    fun open(activity: Activity) {
        val p = Prefs(activity)
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URL)))
            p.donatePendingSince = System.currentTimeMillis()
        }
    }

    /** Вызывается при возвращении в приложение. */
    fun onResume(activity: Activity) {
        val p = Prefs(activity)
        val since = p.donatePendingSince
        if (since == 0L) return
        p.donatePendingSince = 0
        if (shouldThank(System.currentTimeMillis() - since)) {
            p.donated = true
            thanks(activity)
        }
    }

    /** Экран «Спасибо» с конфетти. */
    fun thanks(activity: Activity) {
        val ctx = activity
        val dialog = Dialog(ctx, Prefs(ctx).themeRes)
        val root = FrameLayout(ctx).apply { setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorSurface)) }
        val col = ctx.vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(32), 0, ctx.dp(32), 0)
            addView(heartTile(ctx, 110, 55).apply {
                (layoutParams as LinearLayout.LayoutParams).bottomMargin = ctx.dp(28)
            })
            addView(ctx.text("Спасибо!", 30f, bold = true).apply { gravity = Gravity.CENTER })
            addView(ctx.secondary("Ваша поддержка помогает развивать SUSU Tool. Это очень приятно.", 16f).apply {
                gravity = Gravity.CENTER
                setPadding(0, ctx.dp(12), 0, ctx.dp(32))
                setLineSpacing(0f, 1.15f)
            })
            addView(ctx.button("Пожалуйста") { dialog.dismiss() }.apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }
        root.addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        val confetti = Confetti(ctx)
        root.addView(confetti, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dialog.setContentView(root)
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.post { confetti.burst(); root.haptic() }
    }

    private fun heartTile(ctx: Context, sizeDp: Int, radiusDp: Int) = ImageView(ctx).apply {
        setImageResource(R.drawable.ic_heart)
        setColorFilter(0xFFD4537E.toInt())
        val pad = ctx.dp(sizeDp) / 4
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            cornerRadius = ctx.dp(radiusDp).toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(0xFFD4537E.toInt(), 0x30))
        }
        layoutParams = LinearLayout.LayoutParams(ctx.dp(sizeDp), ctx.dp(sizeDp))
    }

    /** Конфетти: цветные бумажки вылетают сверху, кружатся и падают. Касания пропускает. */
    @SuppressLint("ViewConstructor")
    private class Confetti(context: Context) : View(context) {
        private class Bit(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val vr: Float,
                          val w: Float, val h: Float, val color: Int)
        private val colors = intArrayOf(0xFFD4537E.toInt(), 0xFF378ADD.toInt(), 0xFF1D9E75.toInt(),
            0xFFEF9F27.toInt(), 0xFF8E24AA.toInt(), 0xFFE24B4A.toInt())
        private val bits = ArrayList<Bit>()
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var last = 0L

        init { isClickable = false }

        fun burst() {
            val d = resources.displayMetrics.density
            bits.clear()
            repeat(140) {
                bits += Bit(
                    x = Random.nextFloat() * width, y = -Random.nextFloat() * height * 0.5f,
                    vx = (Random.nextFloat() - 0.5f) * 120 * d, vy = (120 + Random.nextFloat() * 220) * d,
                    rot = Random.nextFloat() * 360, vr = (Random.nextFloat() - 0.5f) * 540,
                    w = (6 + Random.nextFloat() * 6) * d, h = (10 + Random.nextFloat() * 8) * d,
                    color = colors[Random.nextInt(colors.size)],
                )
            }
            last = 0L
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 4500
                addUpdateListener { step() }
                start()
            }
        }

        private fun step() {
            val now = System.nanoTime()
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceAtMost(0.05f)
            last = now
            val g = 260 * resources.displayMetrics.density
            for (b in bits) {
                b.vy += g * dt
                b.x += b.vx * dt + kotlin.math.sin((b.y + b.rot) / 60f) * 0.6f
                b.y += b.vy * dt
                b.rot += b.vr * dt
            }
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            for (b in bits) {
                if (b.y > height + 40) continue
                paint.color = b.color
                canvas.save()
                canvas.translate(b.x, b.y)
                canvas.rotate(b.rot)
                canvas.drawRect(-b.w / 2, -b.h / 2, b.w / 2, b.h / 2, paint)
                canvas.restore()
            }
        }
    }
}
