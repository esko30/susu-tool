package ru.susu.calendarsync

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.AttrRes
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** Небольшие помощники для сборки экранов кодом, без отдельного XML на каждый элемент. */
fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

fun Context.color(@AttrRes attr: Int) = MaterialColors.getColor(this, attr, 0)

fun Context.vertical(padding: Int = 0) = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun Context.horizontal() = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = android.view.Gravity.CENTER_VERTICAL
    // Без этого текст разного размера выравнивается по базовой линии и обрезается снизу.
    isBaselineAligned = false
}

fun Context.scroll(content: View) = ScrollView(this).apply {
    isFillViewport = true
    // Не даём переключателям и полям «перетягивать» прокрутку на себя при перестройке экрана.
    descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
    isFocusableInTouchMode = true
    // Иначе Android рисует поверх сфокусированной ленты серую полупрозрачную подсветку.
    defaultFocusHighlightEnabled = false
    addView(content)
}

fun Context.text(
    value: CharSequence,
    sizeSp: Float = 14f,
    bold: Boolean = false,
    @AttrRes colorAttr: Int = com.google.android.material.R.attr.colorOnSurface,
) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    setTextColor(color(colorAttr))
    if (bold) setTypeface(typeface, Typeface.BOLD)
}

fun Context.secondary(value: CharSequence, sizeSp: Float = 13f) =
    text(value, sizeSp, colorAttr = com.google.android.material.R.attr.colorOnSurfaceVariant)

fun Context.title(value: CharSequence) = text(value, 28f, bold = true).apply { setPadding(0, dp(12), 0, dp(12)) }

fun Context.section(value: CharSequence) = text(value, 13f, bold = true, colorAttr = com.google.android.material.R.attr.colorPrimary).apply {
    setPadding(0, dp(24), 0, dp(8))
    isAllCaps = true
    letterSpacing = 0.08f
}

/** Небольшая цветная «таблетка» с текстом. */
fun Context.chip(value: String, tint: Int, filled: Boolean = false) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setTypeface(typeface, Typeface.BOLD)
    setTextColor(if (filled) color(com.google.android.material.R.attr.colorOnPrimary) else tint)
    setPadding(dp(10), dp(3), dp(10), dp(3))
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(if (filled) tint else androidx.core.graphics.ColorUtils.setAlphaComponent(tint, 40))
    }
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

fun Context.button(label: String, outlined: Boolean = false, onClick: () -> Unit) =
    MaterialButton(
        this, null,
        if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle
        else com.google.android.material.R.attr.materialButtonStyle,
    ).apply {
        text = label
        setOnClickListener { onClick() }
    }

fun Context.textButton(label: String, onClick: () -> Unit) =
    MaterialButton(this, null, com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text = label
        setOnClickListener { onClick() }
    }

fun Context.switch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) = MaterialSwitch(this).apply {
    text = label
    isChecked = checked
    setOnCheckedChangeListener { _, v -> onChange(v) }
}

/** Лейбл в прозрачной рамке: «ЛЕК», «СЕМ». */
fun Context.outlineTag(value: String, tint: Int) = TextView(this).apply {
    text = value
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
    setTypeface(typeface, Typeface.BOLD)
    letterSpacing = 0.06f
    setTextColor(tint)
    setPadding(dp(6), dp(1), dp(6), dp(1))
    background = android.graphics.drawable.GradientDrawable().apply {
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), tint)
        setColor(android.graphics.Color.TRANSPARENT)
    }
}

/** Карточка-подложка: залитая, без рамки, со скруглёнными углами. */
fun Context.surfaceCard(content: View, onClick: (() -> Unit)? = null) = MaterialCardView(this).apply {
    addView(content)
    radius = dp(16).toFloat()
    cardElevation = 0f
    strokeWidth = 0
    setCardBackgroundColor(color(com.google.android.material.R.attr.colorSurfaceContainer))
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { setMargins(0, dp(5), 0, dp(5)) }
    onClick?.let { setOnClickListener { it() } }
}

fun Context.card(vararg children: View, onClick: (() -> Unit)? = null): MaterialCardView {
    val inner = vertical(16)
    children.forEach { inner.addView(it) }
    return surfaceCard(inner, onClick)
}

/** Выпадающий выбор: кнопка с текущим значением, по нажатию — диалог со списком. */
fun Context.spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit): View {
    var current = selected.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    return MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        text = items.getOrNull(current).orEmpty()
        isAllCaps = false
        gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setIconResource(R.drawable.ic_expand)
        iconGravity = MaterialButton.ICON_GRAVITY_END
        setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        iconTint = android.content.res.ColorStateList.valueOf(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
        cornerRadius = dp(12)
        minHeight = dp(52)
        setPadding(dp(16), paddingTop, dp(12), paddingBottom)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(0, dp(4), 0, dp(4)) }
        setOnClickListener {
            MaterialAlertDialogBuilder(context)
                .setSingleChoiceItems(items.toTypedArray(), current) { d, which ->
                    d.dismiss()
                    if (which != current) {
                        // Кнопка сразу показывает выбранный вариант, даже если экран не перестраивается.
                        current = which
                        text = items[which]
                        onSelect(which)
                    }
                }
                .show()
        }
    }
}

fun View.weight(w: Float = 1f): View {
    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
    return this
}
