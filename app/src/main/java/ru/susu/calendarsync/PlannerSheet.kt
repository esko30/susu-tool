package ru.susu.calendarsync

import android.content.Context
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Панель записи планера: к какой паре (эта / следующая / через одну…), метка, заметка, напоминание.
 * [lesson] — пара, с которой открыли; [existing] — редактируемая запись.
 */
object PlannerSheet {
    fun show(ctx: Context, lesson: Lesson?, existing: PlannerItem?, lessons: List<Lesson>, onChanged: () -> Unit) {
        val subject = existing?.subject ?: lesson?.subject ?: return
        val from = lesson ?: lessons.firstOrNull { it.key.startsWith(existing!!.lessonKey) }
        val sheet = BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        box.addView(ctx.secondary(if (existing == null) "Добавить в планер" else "Запись в планере"))
        box.addView(ctx.text(subject, 20f, bold = true).apply { setPadding(0, ctx.dp(2), 0, ctx.dp(4)) })

        // К какой паре: ближайшие пары этого предмета начиная с выбранной.
        data class Target(val date: String, val begin: String, val location: String, val title: String)
        val occ = if (from != null) Planner.occurrences(lessons, subject, from) else emptyList()
        val names = listOf("Эта пара", "Следующая", "Через одну")
        val targets = occ.mapIndexed { i, l ->
            Target(l.date, l.beginTime, l.location, "${names.getOrNull(i)?.let { "$it · " } ?: ""}${ChangeDiff.day(l.date)}, ${l.beginTime}")
        }.toMutableList()
        if (existing != null && targets.none { it.date == existing.date && it.begin == existing.begin }) {
            targets.add(0, Target(existing.date, existing.begin, existing.location, "${ChangeDiff.day(existing.date)}, ${existing.begin}"))
        }
        box.addView(caption(ctx, "К какой паре"))
        val targetGroup = chips(ctx, targets.map { it.title }, targets.indexOfFirst {
            existing == null && it.date == from?.date && it.begin == from.beginTime ||
                existing != null && it.date == existing.date && it.begin == existing.begin
        }.coerceAtLeast(0), required = true)
        box.addView(targetGroup)

        box.addView(caption(ctx, "Метка"))
        val labels = Planner.LABELS.keys.toList()
        val labelGroup = chips(ctx, labels, labels.indexOf(existing?.label), required = false)
        box.addView(labelGroup)

        val note = TextInputEditText(ctx).apply {
            setText(existing?.note.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        }
        box.addView(field(ctx, "Заметка: что повторить, что сдать", note))

        box.addView(caption(ctx, "Фото"))
        val photos = PlannerPhotos.Editor(ctx, existing?.photos.orEmpty())
        box.addView(photos.view)
        var saved = false
        sheet.setOnDismissListener { if (!saved) photos.discard() }

        box.addView(caption(ctx, "Напоминания"))
        val reminders = remindersEditor(ctx, existing?.reminders ?: listOf(Remind.EVENING), lesson = true)
        box.addView(reminders.first)

        val actions = ctx.horizontal().apply { setPadding(0, ctx.dp(16), 0, 0) }
        if (existing != null) {
            actions.addView(ctx.textButton("Удалить") {
                Planner.delete(ctx, existing.id)
                sheet.dismiss()
                onChanged()
            }.weight())
            actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(8), 1) })
        }
        actions.addView(ctx.button("Сохранить") {
            val t = targets.getOrNull(checkedIndex(targetGroup)) ?: return@button
            val label = labels.getOrNull(checkedIndex(labelGroup)).orEmpty()
            val text = note.text?.toString()?.trim().orEmpty()
            if (label.isEmpty() && text.isEmpty()) {
                note.error = "Выберите метку или напишите заметку"
                return@button
            }
            Planner.save(ctx, PlannerItem(
                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                subject = subject, date = t.date, begin = t.begin, location = t.location,
                label = label, note = text,
                reminders = reminders.second(),
                done = existing?.done ?: false,
                photos = photos.photos,
            ))
            saved = true
            photos.commit()
            sheet.dismiss()
            onChanged()
        }.weight())
        box.addView(actions)

        open(sheet, ctx, box)
    }

    /** Своя запись без привязки к паре: название, дата, время (необязательно), метка, заметка, напоминание. */
    fun showCustom(activity: androidx.fragment.app.FragmentActivity, existing: PlannerItem?, onChanged: () -> Unit) {
        val ctx: Context = activity
        val sheet = BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        // Шапка: заголовок и «Сохранить» справа, чтобы кнопка всегда была на виду.
        val header = ctx.horizontal().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        header.addView(ctx.text(if (existing == null) "Новая запись" else "Запись в планере", 20f, bold = true).weight())
        box.addView(header)

        val title = TextInputEditText(ctx).apply {
            setText(existing?.subject.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        val titleLayout = field(ctx, "Что нужно сделать", title)
        box.addView(titleLayout)

        // Дата и время: по умолчанию завтра, без времени.
        var date = existing?.date?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
            ?: Clock.today().plusDays(1)
        var time = existing?.begin.orEmpty()
        val when_ = ctx.horizontal().apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(12) }
        }
        val dateValue = ctx.text("", 16f, bold = true)
        val timeValue = ctx.text("", 16f, bold = true)
        fun refreshWhen() {
            dateValue.text = ChangeDiff.day(date.toString()).replaceFirstChar { it.uppercase() }
            timeValue.text = time.ifBlank { "Без времени" }
        }
        val dateTile = tile(ctx, "Дата", dateValue) {
            val picker = com.google.android.material.datepicker.MaterialDatePicker.Builder.datePicker()
                .setTitleText("Дата")
                .setSelection(date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
                .build()
            picker.addOnPositiveButtonClickListener { ms ->
                date = java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                refreshWhen()
            }
            picker.show(activity.supportFragmentManager, "planner_date")
        }
        val timeTile = tile(ctx, "Время", timeValue) {
            val (h, m) = time.split(":").mapNotNull { it.toIntOrNull() }.let { (it.getOrNull(0) ?: 9) to (it.getOrNull(1) ?: 0) }
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
                .setHour(h).setMinute(m).setTitleText("Время")
                .setNegativeButtonText("Без времени")
                .build()
            picker.addOnPositiveButtonClickListener { time = "%02d:%02d".format(picker.hour, picker.minute); refreshWhen() }
            picker.addOnNegativeButtonClickListener { time = ""; refreshWhen() }
            picker.show(activity.supportFragmentManager, "planner_time")
        }
        when_.addView(dateTile.weight())
        when_.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(10), 1) })
        when_.addView(timeTile.weight())
        refreshWhen()
        box.addView(when_)

        box.addView(caption(ctx, "Метка"))
        val labels = Planner.LABELS.keys.toList()
        val labelGroup = chips(ctx, labels, labels.indexOf(existing?.label), required = false)
        box.addView(labelGroup)

        val note = TextInputEditText(ctx).apply {
            setText(existing?.note.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
        }
        box.addView(field(ctx, "Заметка", note))

        box.addView(caption(ctx, "Фото"))
        val photos = PlannerPhotos.Editor(ctx, existing?.photos.orEmpty())
        box.addView(photos.view)
        var saved = false
        sheet.setOnDismissListener { if (!saved) photos.discard() }

        box.addView(caption(ctx, "Напоминания"))
        val reminders = remindersEditor(ctx, existing?.reminders ?: listOf(Remind.EVENING), lesson = false)
        box.addView(reminders.first)

        val actions = ctx.horizontal().apply { setPadding(0, ctx.dp(16), 0, 0) }
        if (existing != null) {
            actions.addView(ctx.textButton("Удалить") { Planner.delete(ctx, existing.id); saved = true; photos.discard(); sheet.dismiss(); onChanged() }.weight())
            actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(8), 1) })
        }
        actions.addView(ctx.button("Сохранить") {
            val text = title.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) { titleLayout.error = "Напишите, что нужно сделать"; return@button }
            Planner.save(ctx, PlannerItem(
                id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                subject = text, date = date.toString(), begin = time, location = "",
                label = labels.getOrNull(checkedIndex(labelGroup)).orEmpty(),
                note = note.text?.toString()?.trim().orEmpty(),
                reminders = reminders.second(),
                done = existing?.done ?: false, custom = true,
                photos = photos.photos,
            ))
            saved = true
            photos.commit()
            sheet.dismiss()
            onChanged()
        }.weight())
        val save = actions.getChildAt(actions.childCount - 1)
        actions.removeView(save)
        save.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        header.addView(save)
        if (actions.childCount > 0) box.addView(actions)

        open(sheet, ctx, box)
    }

    /**
     * Несколько напоминаний сразу: быстрые варианты чипами (можно выбрать любые) и «＋ Своё…» —
     * за N минут / часов / дней, для дней — в выбранное время. Ничего не выбрано — без напоминаний.
     * Возвращает вид и функцию, отдающую выбранное.
     */
    private fun remindersEditor(ctx: Context, initial: List<Remind>, lesson: Boolean): Pair<View, () -> List<Remind>> {
        val group = chipGroup(ctx)
        val options = ArrayList<Remind>()
        val hint = ctx.secondary("", 12f).apply { setPadding(ctx.dp(2), ctx.dp(8), 0, 0) }
        fun refreshHint() {
            val n = (0 until group.childCount).count { (group.getChildAt(it) as Chip).let { c -> c.isCheckable && c.isChecked } }
            hint.text = if (n == 0) "Без напоминаний" else "Выбрано: $n. Можно отметить несколько."
        }
        fun addOption(r: Remind, checked: Boolean) {
            if (r in options) {
                (group.getChildAt(options.indexOf(r)) as Chip).isChecked = true
                refreshHint(); return
            }
            options += r
            group.addView(chip(ctx, r.label(lesson)).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, _ -> refreshHint() }
            }, options.size - 1)
        }
        val add = chip(ctx, "＋ Своё…").apply {
            isCheckable = false
            setOnClickListener { customDialog(ctx) { addOption(it, true) } }
        }
        group.addView(add)
        (Remind.PRESETS + initial).distinct().forEach { addOption(it, it in initial) }
        refreshHint()
        val box = ctx.vertical().apply { addView(group); addView(hint) }
        return box to {
            options.filterIndexed { i, _ -> (group.getChildAt(i) as Chip).isChecked }
        }
    }

    /** Окно «Своё напоминание»: число + единица; для дней — время срабатывания. */
    private fun customDialog(ctx: Context, onPick: (Remind) -> Unit) {
        val box = ctx.vertical(20)
        val num = TextInputEditText(ctx).apply { inputType = InputType.TYPE_CLASS_NUMBER; setText("30") }
        box.addView(field(ctx, "За сколько", num))
        val units = listOf("минут", "часов", "дней")
        val unitGroup = chips(ctx, units, 0, required = true)
        box.addView(unitGroup)
        var time = "20:00"
        val timeBtn = ctx.button("В $time", outlined = true) {}
        timeBtn.setOnClickListener {
            val act = ctx as? androidx.fragment.app.FragmentActivity ?: return@setOnClickListener
            val (h, m) = time.split(":").map { it.toInt() }
            val picker = com.google.android.material.timepicker.MaterialTimePicker.Builder()
                .setTimeFormat(com.google.android.material.timepicker.TimeFormat.CLOCK_24H)
                .setHour(h).setMinute(m).setTitleText("Во сколько напомнить").build()
            picker.addOnPositiveButtonClickListener {
                time = "%02d:%02d".format(picker.hour, picker.minute)
                timeBtn.text = "В $time"
            }
            picker.show(act.supportFragmentManager, "remind_time")
        }
        timeBtn.visibility = View.GONE
        box.addView(timeBtn)
        box.addView(ctx.secondary("Для дней: 0 значит в тот же день, 1 значит накануне.", 12f))
        unitGroup.setOnCheckedStateChangeListener { _, _ ->
            timeBtn.visibility = if (checkedIndex(unitGroup) == 2) View.VISIBLE else View.GONE
            if (checkedIndex(unitGroup) == 2 && num.text?.toString() == "30") num.setText("1")
        }
        com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("Своё напоминание")
            .setView(box)
            .setPositiveButton("Добавить") { _, _ ->
                val n = num.text?.toString()?.toIntOrNull()?.coerceIn(0, 365 * 24 * 60) ?: return@setPositiveButton
                onPick(when (checkedIndex(unitGroup)) {
                    1 -> Remind(minutes = n * 60)
                    2 -> Remind(days = n.coerceAtMost(365), time = time)
                    else -> Remind(minutes = n)
                })
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun caption(ctx: Context, text: String) =
        ctx.text(text, 14f, bold = true).apply { setPadding(ctx.dp(2), ctx.dp(18), 0, ctx.dp(6)) }

    /** Поле ввода в рамке со скруглением, как в остальном приложении. */
    private fun field(ctx: Context, hint: String, edit: TextInputEditText) =
        TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeWidth = ctx.dp(1)
            boxStrokeWidthFocused = ctx.dp(2)
            boxStrokeColor = ctx.color(com.google.android.material.R.attr.colorPrimary)
            setBoxStrokeColorStateList(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(ctx.color(com.google.android.material.R.attr.colorPrimary), ctx.color(com.google.android.material.R.attr.colorOutline)),
            ))
            val r = ctx.dp(14).toFloat()
            setBoxCornerRadii(r, r, r, r)
            edit.background = null
            edit.setPadding(ctx.dp(16), ctx.dp(16), ctx.dp(16), ctx.dp(16))
            addView(edit)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = ctx.dp(12) }
        }

    /** Плитка «подпись + значение» для даты и времени. */
    private fun tile(ctx: Context, label: String, value: View, onClick: () -> Unit) =
        com.google.android.material.card.MaterialCardView(ctx).apply {
            radius = ctx.dp(14).toFloat()
            cardElevation = 0f
            strokeWidth = ctx.dp(1)
            strokeColor = ctx.color(com.google.android.material.R.attr.colorOutline)
            setCardBackgroundColor(android.graphics.Color.TRANSPARENT)
            addView(ctx.vertical().apply {
                setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
                addView(ctx.secondary(label, 12f))
                addView(value)
            })
            setOnClickListener { onClick() }
        }

    /** Нижняя панель открывается сразу целиком, без половинного положения. */
    private fun open(sheet: BottomSheetDialog, ctx: Context, box: View) {
        box.setPadding(box.paddingLeft, box.paddingTop, box.paddingRight, box.paddingBottom + ctx.dp(28))
        sheet.setContentView(ctx.scroll(box))
        sheet.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        sheet.behavior.skipCollapsed = true
        sheet.show()
    }

    private fun chipGroup(ctx: Context) = ChipGroup(ctx).apply {
        chipSpacingHorizontal = ctx.dp(8)
        chipSpacingVertical = ctx.dp(8)
    }

    /** Компактный чип без лишних отступов вокруг. */
    private fun chip(ctx: Context, t: String) = Chip(ctx).apply {
        id = View.generateViewId()
        text = t
        isCheckable = true
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = ctx.dp(36).toFloat()
    }

    /** Группа чипов с одиночным выбором; [selected] = -1 — ничего не выбрано. */
    private fun chips(ctx: Context, items: List<String>, selected: Int, required: Boolean) = chipGroup(ctx).apply {
        isSingleSelection = true
        isSelectionRequired = required
        items.forEachIndexed { i, t -> addView(chip(ctx, t).apply { isChecked = i == selected }) }
    }

    private fun checkedIndex(group: ChipGroup) =
        (0 until group.childCount).firstOrNull { (group.getChildAt(it) as Chip).isChecked } ?: -1
}
