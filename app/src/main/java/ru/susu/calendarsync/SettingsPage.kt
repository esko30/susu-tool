package ru.susu.calendarsync

import android.text.InputType
import android.view.View
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.core.util.Pair as APair
import androidx.lifecycle.lifecycleScope
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Аккаунт, синхронизация с календарём, автообновление и уведомления. */
class SettingsPage : Page() {
    private companion object {
        const val DONATE_URL = "https://finance.ozon.ru/apps/sbp/ozonbankpay/01a1b512-8b85-766c-84dc-2ce3951ada42"
    }

    private var loading = false

    private val reminderValues = listOf(0, 5, 10, 15, 30, 60)
    private val reminderLabels = listOf("Без напоминания", "За 5 минут", "За 10 минут", "За 15 минут", "За 30 минут", "За 1 час")
    private val intervalValues = listOf(0, 1, 3, 6, 12, 24)
    private val intervalLabels = listOf("Выключено", "Каждый час", "Каждые 3 часа", "Каждые 6 часов", "Каждые 12 часов", "Раз в сутки")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val root = ctx.vertical(16)
        root.addView(ctx.title("Настройки"))

        // Аккаунт.
        root.addView(ctx.section("Аккаунт ЮУрГУ-Онлайн"))
        if (prefs.displayName.isNotBlank()) root.addView(ctx.secondary(prefs.displayName))
        val loginInput = TextInputEditText(ctx).apply {
            setText(prefs.login)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        root.addView(TextInputLayout(ctx).apply { hint = "Логин"; addView(loginInput) })
        val passInput = TextInputEditText(ctx).apply {
            setText(prefs.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(TextInputLayout(ctx).apply {
            hint = "Пароль"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(passInput)
        })
        root.addView(ctx.button(if (loading) "Синхронизация…" else "Сохранить и синхронизировать всё") {
            val login = loginInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (login.isEmpty() || pass.isEmpty()) {
                prefs.lastStatus = "Заполните логин и пароль"
                render()
                return@button
            }
            if (login != prefs.login || pass != prefs.password) Session.reset()
            prefs.login = login
            prefs.password = pass
            main.withPermissions { syncAll() }
        }.apply { isEnabled = !loading })
        if (prefs.lastStatus.isNotBlank()) {
            root.addView(ctx.secondary(prefs.lastStatus).apply { setPadding(0, ctx.dp(6), 0, 0); setTextIsSelectable(true) })
        }

        // Внешний вид — отдельный экран.
        root.addView(ctx.section("Внешний вид"))
        root.addView(ctx.card(
            ctx.text("Внешний вид  ›", 16f, bold = true),
            ctx.secondary("Тема, карточки пар, прогресс и анимации, пропуск и подсказка"),
        ) { main.openAppearance() })

        // Календарь.
        root.addView(ctx.section("Календарь телефона"))
        root.addView(ctx.secondary("В календарь попадают расписания с включённым «Добавлять в календарь» (вкладка «Расписание»). Для каждого создаётся отдельный календарь «SUSU Tool — …»."))
        root.addView(ctx.text("Промежуток синхронизации", 14f).apply { setPadding(0, ctx.dp(10), 0, 0) })
        val ranges = CalendarRange.values().toList()
        root.addView(ctx.spinner(ranges.map { it.label }, ranges.indexOf(prefs.calendarRange)) { i ->
            if (ranges[i] == CalendarRange.CUSTOM) pickRange(prefs) else {
                prefs.calendarRange = ranges[i]
                render()
            }
        })
        if (prefs.calendarRange == CalendarRange.CUSTOM) {
            val w = DateWindow.of(prefs)
            root.addView(ctx.secondary("${w.from?.format(dateFmt) ?: "…"} — ${w.to?.format(dateFmt) ?: "…"}"))
            root.addView(ctx.textButton("Изменить даты") { pickRange(prefs) })
        }
        root.addView(ctx.text("Напоминание перед занятием", 14f).apply { setPadding(0, ctx.dp(10), 0, 0) })
        root.addView(ctx.spinner(reminderLabels, reminderValues.indexOf(prefs.reminderMinutes).coerceAtLeast(0)) { i ->
            prefs.reminderMinutes = reminderValues[i]
        })
        root.addView(ctx.secondary("Новые настройки календаря применяются при следующей синхронизации."))

        // Автообновление и уведомления.
        root.addView(ctx.section("Автообновление и уведомления"))
        root.addView(ctx.text("Проверять изменения", 14f))
        root.addView(ctx.spinner(intervalLabels, intervalValues.indexOf(prefs.syncIntervalHours).coerceAtLeast(0)) { i ->
            prefs.syncIntervalHours = intervalValues[i]
            SyncWorker.schedule(ctx.applicationContext)
        })
        root.addView(ctx.switch("Уведомлять о новых КРМ и изменениях оценок", prefs.monitorGrades) { prefs.monitorGrades = it })
        root.addView(ctx.secondary("За изменениями в расписаниях следят те, у которых включено «Следить за изменениями» на вкладке «Расписание»."))

        // Звуки уведомлений: встроенные, системный, без звука или любой из телефона. При выборе звук проигрывается.
        fun soundPicker(label: String, current: String, save: (String) -> Unit) {
            root.addView(ctx.text(label, 14f).apply { setPadding(0, ctx.dp(12), 0, 0) })
            val keys = Notifier.SOUNDS.keys.toList()
            val labels = Notifier.SOUNDS.values.toList() + "Из звуков телефона…"
            val index = if (current.startsWith("uri:")) labels.size - 1 else keys.indexOf(current).coerceAtLeast(0)
            val shown = labels.toMutableList().apply { if (current.startsWith("uri:")) this[size - 1] = Notifier.soundLabel(ctx, current) }
            root.addView(ctx.spinner(shown, index) { i ->
                if (i == keys.size) pickPhoneSound(save) else {
                    save(keys[i])
                    Notifier.ensureChannels(ctx)
                    Notifier.preview(ctx, keys[i])
                    render()
                }
            })
        }
        soundPicker("Звук: изменения в расписании", prefs.soundSchedule) { prefs.soundSchedule = it }
        soundPicker("Звук: оценки и КРМ", prefs.soundGrades) { prefs.soundGrades = it }

        // Обновления через GitHub Releases.
        root.addView(ctx.section("Обновления"))
        root.addView(ctx.secondary("Установлена версия ${BuildConfig.VERSION_NAME}" +
            if (updateStatus.isNotBlank()) "\n$updateStatus" else ""))
        root.addView(ctx.switch("Проверять обновления при запуске", prefs.autoUpdateCheck) { prefs.autoUpdateCheck = it })
        root.addView(ctx.button("Проверить обновления", outlined = true) {
            updateStatus = "Проверяю…"
            render()
            Updater.checkNow(requireActivity(), viewLifecycleOwner.lifecycleScope) { updateStatus = it; render() }
        }.apply {
            isEnabled = Updater.enabled
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(4) }
        })

        // О приложении и данных.
        root.addView(ctx.section("О приложении и данных"))
        root.addView(ctx.card(
            ctx.text("SUSU Tool — неофициальное приложение", 15f, bold = true),
            ctx.secondary(
                "Приложение работает через те же официальные запросы, что и ЮУрГУ-Онлайн (online.susu.ru): " +
                    "вход, расписание, поиск, успеваемость и пропуск. Других серверов у приложения нет — " +
                    "данные никуда, кроме online.susu.ru, не передаются, аналитики и рекламы нет.\n\n" +
                    "Все права на систему ЮУрГУ-Онлайн и её данные принадлежат ФГАОУ ВО «ЮУрГУ (НИУ)». " +
                    "Разработчик SUSU Tool не связан с университетом и ни на что не претендует.", 13f,
            ).apply { setPadding(0, ctx.dp(6), 0, 0); setLineSpacing(0f, 1.15f) },
        ))
        root.addView(ctx.card(
            ctx.text("Что хранится на телефоне", 15f, bold = true),
            ctx.secondary(
                "• Логин и пароль — в зашифрованном хранилище Android (ключ в Android Keystore), чтобы входить автоматически.\n" +
                    "• Сохранённые расписания, оценки и баллы КРМ, история изменений — в личной папке приложения, " +
                    "недоступной другим приложениям.\n" +
                    "• Код пропуска — в зашифрованном хранилище, чтобы пропуск открывался без интернета.\n" +
                    "• Занятия — в отдельных локальных календарях «SUSU Tool — …» на телефоне, для расписаний с включённым «Добавлять в календарь». Они не синхронизируются с Google.\n\n" +
                    "Всё удаляется кнопкой «Выйти и удалить все данные и календари» или при удалении приложения.", 13f,
            ).apply { setPadding(0, ctx.dp(6), 0, 0); setLineSpacing(0f, 1.15f) },
        ))

        // Поддержка проекта.
        root.addView(ctx.section("Поддержать SUSU Tool"))
        root.addView(ctx.secondary("Если приложение помогает — можно поддержать разработку переводом через СБП."))
        root.addView(ctx.button("Пожертвовать") {
            runCatching {
                startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(DONATE_URL)))
            }
        }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(8) }
        })

        // Выход.
        root.addView(ctx.section("Выход"))
        root.addView(ctx.textButton("Выйти и удалить все данные и календари") { confirmReset() })
        root.addView(ctx.secondary("SUSU Tool · версия ${BuildConfig.VERSION_NAME}").apply { setPadding(0, ctx.dp(16), 0, 0) })
        return ctx.scroll(root)
    }

    private var updateStatus = ""
    private var pendingSound: ((String) -> Unit)? = null
    private val ringtonePicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri: android.net.Uri? = result.data?.let {
            @Suppress("DEPRECATION") it.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        }
        val save = pendingSound
        pendingSound = null
        if (uri != null && save != null) {
            val ctx = requireContext()
            save("uri:$uri")
            Notifier.ensureChannels(ctx)
            Notifier.preview(ctx, "uri:$uri")
        }
        render()
    }

    /** Системный выбор звука уведомления из телефона. */
    private fun pickPhoneSound(save: (String) -> Unit) {
        pendingSound = save
        ringtonePicker.launch(android.content.Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_NOTIFICATION)
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "Звук уведомления")
            putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        })
    }

    private fun pickRange(prefs: Prefs) {
        val w = DateWindow.of(prefs)
        val today = LocalDate.now(CalendarSync.SUSU_ZONE)
        fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Промежуток для календаря")
            .setSelection(APair((w.from ?: today).utcMillis(), (w.to ?: today.plusMonths(1)).utcMillis()))
            .build()
        picker.addOnPositiveButtonClickListener { sel ->
            val from = Instant.ofEpochMilli(sel.first).atZone(ZoneOffset.UTC).toLocalDate()
            val to = Instant.ofEpochMilli(sel.second).atZone(ZoneOffset.UTC).toLocalDate()
            prefs.customFrom = from.toEpochDay()
            prefs.customTo = to.toEpochDay()
            prefs.calendarRange = CalendarRange.CUSTOM
            render()
        }
        picker.addOnDismissListener { render() }
        picker.show(parentFragmentManager, "range")
    }

    private fun syncAll() {
        val ctx = requireContext().applicationContext
        val prefs = Prefs(ctx)
        loading = true
        prefs.lastStatus = "Синхронизация…"
        render()
        background({ Syncer.run(ctx) }) { r ->
            loading = false
            prefs.lastStatus = r.getOrElse { errorText(it) }
            SyncWorker.schedule(ctx)
            render()
        }
    }

    private fun confirmReset() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Выйти?")
            .setMessage("Будут удалены все календари SUSU Tool с занятиями, сохранённые расписания, оценки, история изменений и учётные данные.")
            .setPositiveButton("Удалить") { _, _ ->
                val app = ctx.applicationContext
                background({
                    Prefs(app).syncIntervalHours = 0
                    SyncWorker.schedule(app)
                    if (Syncer.hasCalendarPermission(app)) CalendarSync(app).deleteAll()
                    Store(app).clearAll()
                    GradesStore(app).clearAll()
                    ChangeLog(app).clear()
                    Prefs(app).clear()
                    Session.reset()
                }) {
                    main.tempSchedule = null
                    main.selectedUid = null
                    render()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }
}
