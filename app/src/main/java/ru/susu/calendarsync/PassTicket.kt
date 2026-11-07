package ru.susu.calendarsync

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

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

    /**
     * Полноэкранный пропуск: плавно выезжает слева, яркость экрана — максимальная, пока он открыт.
     * Закрывается касанием, смахиванием влево или кнопкой «Назад».
     */
    fun show(context: Context, scope: CoroutineScope) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val dp = { v: Int -> context.dp(v) }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.WHITE)
            setPadding(dp(28), dp(28), dp(28), dp(28))
        }
        fun label(text: String, size: Float, bold: Boolean = false, color: Int = Color.BLACK) = TextView(context).apply {
            this.text = text
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            setTextColor(color)
            gravity = Gravity.CENTER
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        card.addView(label("Пропуск ЮУрГУ", 24f, bold = true))
        Prefs(context).displayName.takeIf { it.isNotBlank() }?.let {
            card.addView(label(it, 16f, color = Color.DKGRAY).apply { setPadding(0, dp(6), 0, 0) })
        }
        val qrSize = (context.resources.displayMetrics.widthPixels * 0.78f).toInt()
        val holder = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(qrSize, qrSize).apply { topMargin = dp(24); bottomMargin = dp(16) }
            addView(ProgressBar(context), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
        }
        card.addView(holder)
        val number = label("", 15f, color = Color.GRAY)
        card.addView(number)
        card.addView(label("Коснитесь или смахните влево, чтобы закрыть", 13f, color = Color.GRAY).apply { setPadding(0, dp(24), 0, 0) })

        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.WHITE)
            addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }

        fun close() {
            root.animate().translationX(-root.width * 0.35f).alpha(0f).setDuration(220)
                .withEndAction { dialog.dismiss() }.start()
        }
        var downX = 0f
        root.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> downX = e.x
                MotionEvent.ACTION_UP -> if (downX - e.x > dp(80) || abs(downX - e.x) < dp(10)) close()
            }
            true
        }
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) { close(); true } else false
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            // Максимальная яркость только пока открыт пропуск — сканеру так проще прочитать код.
            attributes = attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setWindowAnimations(0)
        }
        root.alpha = 0f
        root.translationX = -context.resources.displayMetrics.widthPixels * 0.35f
        dialog.show()
        root.animate().translationX(0f).alpha(1f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()

        val app = context.applicationContext
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { get(app).let { it to qr(it, qrSize) } } }
            holder.removeAllViews()
            result.onSuccess { (code, bmp) ->
                holder.addView(ImageView(context).apply {
                    setImageBitmap(bmp)
                    alpha = 0f
                    animate().alpha(1f).setDuration(250).start()
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                number.text = code
            }.onFailure {
                holder.addView(label("Не удалось получить пропуск.\nПроверьте интернет и вход в аккаунт.", 16f, color = Color.DKGRAY),
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            }
        }
    }
}
