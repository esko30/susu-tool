package ru.susu.calendarsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Электронный пропуск ЮУрГУ. Портал отдаёт числовой код (/api/User/GetPassTicket), веб-версия
 * показывает его QR-кодом и обновляет раз в 12 часов. Код хранится в зашифрованных настройках,
 * чтобы пропуск открывался и без интернета.
 */
object PassTicket {
    private const val REFRESH_MS = 12 * 3600_000L
    private val DIGITS = Regex("^\\d+$")

    /** Код пропуска: свежий из кэша, иначе с портала; при ошибке — последний сохранённый. */
    fun get(context: Context): String {
        val prefs = Prefs(context)
        val cached = prefs.passTicket
        if (cached.isNotEmpty() && System.currentTimeMillis() - prefs.passTicketUpdated < REFRESH_MS) return cached
        return try {
            val raw = Session.call(context) { SusuApi.get(it, "/api/User/GetPassTicket") }
            val code = raw.trim().trim('"')
            check(DIGITS.matches(code)) { "Портал не выдал пропуск" }
            prefs.passTicket = code
            prefs.passTicketUpdated = System.currentTimeMillis()
            code
        } catch (e: Exception) {
            if (cached.isNotEmpty()) cached else throw e
        }
    }

    fun qr(code: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q, EncodeHintType.MARGIN to 1)
        val m = QRCodeWriter().encode(code, BarcodeFormat.QR_CODE, size, size, hints)
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/**
 * Вкладка «Пропуск»: QR-код на белом фоне. Пока вкладка открыта, яркость экрана максимальная
 * и экран не гаснет; при уходе с вкладки всё возвращается как было.
 */
class PassPage : Page() {
    override fun build(): View {
        val ctx = requireContext()
        // В стиле приложения: фон темы, QR — в белой карточке (белый фон нужен для считывания).
        val col = ctx.vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(24), ctx.dp(24), ctx.dp(24), ctx.dp(24))
        }
        col.addView(ctx.text("ПРОПУСК ЮУрГУ", 13f, bold = true, colorAttr = com.google.android.material.R.attr.colorPrimary).apply {
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
        })
        Prefs(ctx).displayName.takeIf { it.isNotBlank() }?.let {
            col.addView(ctx.text(it, 22f, bold = true).apply { gravity = Gravity.CENTER; setPadding(0, ctx.dp(6), 0, 0) })
        }

        val qrSize = (ctx.resources.displayMetrics.widthPixels * 0.62f).toInt()
        val holder = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize)
            addView(ProgressBar(ctx), FrameLayout.LayoutParams(ctx.dp(48), ctx.dp(48), Gravity.CENTER))
        }
        val number = ctx.text("", 17f, bold = true).apply {
            setTextColor(Color.rgb(32, 33, 36))
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setPadding(0, ctx.dp(14), 0, 0)
        }
        val inner = ctx.vertical().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ctx.dp(22), ctx.dp(22), ctx.dp(22), ctx.dp(18))
            addView(holder)
            addView(number)
        }
        val card = com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = ctx.dp(28).toFloat()
            setCardBackgroundColor(Color.WHITE)
            cardElevation = ctx.dp(6).toFloat()
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(22); bottomMargin = ctx.dp(18) }
            addView(inner)
        }
        col.addView(card)

        val root = FrameLayout(ctx).apply {
            setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorSurface))
            addView(col, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        // Плавное появление.
        col.alpha = 0f
        col.translationY = ctx.dp(16).toFloat()
        col.animate().alpha(1f).translationY(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()

        if (!Prefs(ctx).hasCredentials) {
            holder.removeAllViews()
            holder.addView(ctx.text("Войдите в аккаунт на вкладке «Настройки», чтобы получить пропуск.", 16f).apply {
                setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            return root
        }
        val app = ctx.applicationContext
        background({ PassTicket.get(app).let { it to PassTicket.qr(it, qrSize) } }) { r ->
            holder.removeAllViews()
            r.onSuccess { (code, bmp) ->
                holder.addView(ImageView(ctx).apply {
                    setImageBitmap(bmp)
                    alpha = 0f
                    animate().alpha(1f).setDuration(250).start()
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                number.text = code
            }.onFailure {
                holder.addView(ctx.text("Не удалось получить пропуск.\nПроверьте интернет и вход в аккаунт.", 16f).apply {
                    setTextColor(Color.DKGRAY); gravity = Gravity.CENTER
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
        }
        return root
    }

    override fun onResume() {
        super.onResume()
        setBright(true)
    }

    override fun onPause() {
        setBright(false)
        super.onPause()
    }

    /** Максимальная яркость и запрет гашения экрана — только пока открыт пропуск. */
    private fun setBright(on: Boolean) {
        val window = activity?.window ?: return
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
