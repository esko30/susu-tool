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
    /** Вернулись из системных настроек (например, разрешили работу в фоне) — обновляем экран. */
    private var resumedOnce = false
    override fun onResume() {
        super.onResume()
        if (resumedOnce) render() else resumedOnce = true
    }

    companion object {
        const val SECTION_CALENDAR = "calendar"
        const val SECTION_NOTIFY = "notify"
        const val SECTION_ABOUT = "about"

        fun of(section: String) = SettingsPage().apply { arguments = android.os.Bundle().apply { putString("section", section) } }

    }

    private var loading = false
    private val section get() = arguments?.getString("section")

    /** Подэкран настроек: «‹ Настройки», заголовок и содержимое. */
    private fun subPage(title: String, fill: (android.widget.LinearLayout) -> Unit): View {
        val ctx = requireContext()
        val root = ctx.vertical(16)
        root.addView(ctx.textButton("‹ Настройки") { parentFragmentManager.popBackStack() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        })
        root.addView(ctx.title(title))
        fill(root)
        return ctx.scroll(root)
    }

    private fun fullWidth(v: View) = v.apply {
        layoutParams = android.widget.LinearLayout.LayoutParams(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = requireContext().dp(4) }
    }


    /** Телефон ограничивает работу в фоне — показываем, как это исправить. */
    private fun batteryWarning(root: android.widget.LinearLayout) {
        val ctx = requireContext()
        if (!Battery.restricted(ctx)) return
        val views = mutableListOf<View>(
            ctx.text("Проверки могут задерживаться", 15f, bold = true),
            ctx.secondary("Телефон бережёт батарею и может откладывать проверки на часы. " +
                "Разрешите приложению работать в фоне, и уведомления будут приходить вовремя."),
        )
        Battery.vendorHint()?.let { views += ctx.secondary(it) }
        views += ctx.button("Разрешить работу в фоне", outlined = true) { Battery.open(ctx) }
        root.addView(ctx.card(*views.toTypedArray()))
    }

    private val reminderValues = listOf(0, 5, 10, 15, 30, 60)
    private val reminderLabels = listOf("Без напоминания", "За 5 минут", "За 10 минут", "За 15 минут", "За 30 минут", "За 1 час")
    private val intervalValues = listOf(0, 1, 3, 6, 12, 24)
    private val intervalLabels = listOf("Выключено", "Каждый час", "Каждые 3 часа", "Каждые 6 часов", "Каждые 12 часов", "Раз в сутки")
    private val dateFmt = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale("ru"))

    override fun build(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val root = ctx.vertical(16)

        when (section) {
            SECTION_CALENDAR -> return subPage("Календарь телефона") { root ->
        // Календарь.
        root.addView(ctx.secondary("Сюда попадают расписания, где включено «Добавлять в календарь». " +
            "Для каждого заводится свой календарь."))
        root.addView(ctx.text("За какой период добавлять пары", 14f).apply { setPadding(0, ctx.dp(10), 0, 0) })
        val ranges = CalendarRange.values().toList()
        root.addView(ctx.spinner(ranges.map { it.label }, ranges.indexOf(prefs.calendarRange)) { i ->
            if (ranges[i] == CalendarRange.CUSTOM) pickRange(prefs) else {
                prefs.calendarRange = ranges[i]
                render()
            }
        })
        if (prefs.calendarRange == CalendarRange.CUSTOM) {
            val w = DateWindow.of(prefs)
            root.addView(ctx.secondary("с ${w.from?.format(dateFmt) ?: "…"} по ${w.to?.format(dateFmt) ?: "…"}"))
            root.addView(ctx.textButton("Изменить даты") { pickRange(prefs) })
        }
        root.addView(ctx.text("Напоминать перед парой", 14f).apply { setPadding(0, ctx.dp(10), 0, 0) })
        root.addView(ctx.spinner(reminderLabels, reminderValues.indexOf(prefs.reminderMinutes).coerceAtLeast(0)) { i ->
            prefs.reminderMinutes = reminderValues[i]
        })
        root.addView(ctx.switch("Короткие названия типов, например «Практики»", prefs.display.shortTypes) {
            prefs.display = prefs.display.copy(shortTypes = it)
        })
        root.addView(ctx.secondary("Изменения появятся в календаре после следующего обновления."))

            }
            SECTION_NOTIFY -> return subPage("Уведомления и звуки") { root ->
        // Автообновление и уведомления.
        root.addView(ctx.text("Как часто проверять портал", 14f))
        root.addView(ctx.spinner(intervalLabels, intervalValues.indexOf(prefs.syncIntervalHours).coerceAtLeast(0)) { i ->
            prefs.syncIntervalHours = intervalValues[i]
            SyncWorker.schedule(ctx.applicationContext)
            render()
        })
        if (prefs.syncIntervalHours > 0) {
            val last = prefs.lastBackgroundCheck
            root.addView(ctx.secondary(if (last == 0L) "В фоне ещё ни разу не проверял"
                else "Последний раз проверял " + java.text.SimpleDateFormat("d MMMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(last))))
            batteryWarning(root)
        }
        root.addView(ctx.secondary("Ищу переносы и отмены пар, новые КРМ, баллы и оценки. Переносы ищу в расписаниях, где включено «Следить за изменениями»."))

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
        soundPicker("Звук переносов и отмен", prefs.soundSchedule) { prefs.soundSchedule = it }
        soundPicker("Звук итоговых оценок", prefs.soundGrades) { prefs.soundGrades = it }
        root.addView(ctx.textButton("Показать, как выглядят уведомления") {
            Notifier.notifyGrades(ctx.applicationContext, prefs.currentTerm, GradeNews.sample())
        })

            }
            SECTION_ABOUT -> return subPage("О приложении") { root ->
        // Помощь: тур и приветствие можно открыть снова.
        root.addView(ctx.section("Помощь"))
        root.addView(fullWidth(ctx.button("Тур по приложению", outlined = true) { main.startTour() }))
        root.addView(fullWidth(ctx.button("Приветствие", outlined = true) { main.showOnboarding() }))

        // Обновления через GitHub Releases.
        root.addView(ctx.section("Обновления"))
        root.addView(ctx.secondary("Установлена версия ${BuildConfig.VERSION_NAME}" +
            if (updateStatus.isNotBlank()) "\n$updateStatus" else ""))
        root.addView(ctx.button("Что нового", outlined = true) { WhatsNew.showAll(ctx) }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(4) }
        })
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
            ctx.text("Это неофициальное приложение", 15f, bold = true),
            ctx.secondary(
                "Приложение обращается только к online.susu.ru, теми же запросами, что и сайт. " +
                    "Своих серверов, аналитики и рекламы у него нет.\n\n" +
                    "ЮУрГУ-Онлайн и все его данные принадлежат ЮУрГУ. " +
                    "Разработчик приложения с университетом не связан.", 13f,
            ).apply { setPadding(0, ctx.dp(6), 0, 0); setLineSpacing(0f, 1.15f) },
        ))
        root.addView(ctx.card(
            ctx.text("Что хранится на телефоне", 15f, bold = true),
            ctx.secondary(
                "• Логин и пароль зашифрованы, чтобы входить автоматически.\n" +
                    "• Расписания, оценки и история изменений лежат в папке приложения, другие приложения её не видят.\n" +
                    "• Код пропуска зашифрован, поэтому пропуск открывается без интернета.\n" +
                    "• Пары попадают в календари на самом телефоне. С Google они не синхронизируются.\n\n" +
                    "Кнопка «Выйти» или удаление приложения стирают всё.", 13f,
            ).apply { setPadding(0, ctx.dp(6), 0, 0); setLineSpacing(0f, 1.15f) },
        ))

        root.addView(ctx.secondary("SUSU Tool · версия ${BuildConfig.VERSION_NAME}").apply { setPadding(0, ctx.dp(16), 0, 0) })
            }
        }
        root.addView(ctx.title("Настройки"))
        // Аккаунт.
        root.addView(ctx.section("Аккаунт ЮУрГУ-Онлайн"))
        if (prefs.hasCredentials) root.addView(profileCard()) else {
        val loginInput = TextInputEditText(ctx).apply {
            setText(prefs.login)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        root.addView(ctx.outlinedField("Логин", loginInput).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(8) }
        })
        val passInput = TextInputEditText(ctx).apply {
            setText(prefs.password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        root.addView(ctx.outlinedField("Пароль", passInput).apply {
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = ctx.dp(8) }
        })
        root.addView(ctx.button(if (loading) "Вхожу…" else "Войти") {
            val login = loginInput.text.toString().trim()
            val pass = passInput.text.toString()
            if (login.isEmpty() || pass.isEmpty()) {
                prefs.lastStatus = "Введите логин и пароль"
                render()
                return@button
            }
            if (login != prefs.login || pass != prefs.password) Session.reset()
            prefs.login = login
            prefs.password = pass
            main.withPermissions { syncAll() }
        }.apply { isEnabled = !loading })
        }
        if (!prefs.hasCredentials && prefs.lastStatus.isNotBlank()) {
            root.addView(ctx.secondary(prefs.lastStatus).apply { setPadding(0, ctx.dp(6), 0, 0); setTextIsSelectable(true) })
        }

        // Разделы — отдельные экраны, как «Внешний вид».
        if (prefs.syncIntervalHours > 0) batteryWarning(root)
        fun link(title: String, sub: String, open: () -> Unit) = root.addView(ctx.card(
            ctx.text("$title  ›", 16f, bold = true), ctx.secondary(sub),
        ) { open() })
        root.addView(ctx.section("Настройки"))
        link("Внешний вид", "Тема, шрифт и карточки пар") { main.openAppearance() }
        link("Уведомления и звуки", "Как часто проверять и какие звуки") { main.openSettings(SECTION_NOTIFY) }
        link("Календарь телефона", "Пары в календаре телефона и напоминания") { main.openSettings(SECTION_CALENDAR) }
        // Поддержка проекта.
        root.addView(ctx.section("Поддержать SUSU Tool"))
        root.addView(ctx.card(
            ctx.text("Пожертвовать  ›", 16f, bold = true),
            ctx.secondary("Если приложение помогает, можно поддержать его переводом через СБП."),
        ) { Donate.open(requireActivity()) })


        root.addView(ctx.section("Приложение"))
        link("О приложении", "Помощь, обновления и где хранятся данные") { main.openSettings(SECTION_ABOUT) }
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
            .setTitleText("Период для календаря")
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
        render()
        background({ Syncer.run(ctx) }) { r ->
            loading = false
            prefs.lastStatus = r.getOrElse { errorText(it) }
            SyncWorker.schedule(ctx)
            render()
        }
    }

    /** Аватарка, ФИО и группа с портала; синхронизация и выход. */
    private fun profileCard(): View {
        val ctx = requireContext()
        val prefs = Prefs(ctx)
        val name = prefs.displayName.ifBlank { prefs.login }
        val group = Profile.group(ctx)
        val row = ctx.horizontal().apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        row.addView(Profile.view(ctx, 64, name) { avatarMenu() })
        row.addView(ctx.vertical().apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = ctx.dp(16) }
            addView(ctx.text(name, 18f, bold = true))
            if (group.isNotBlank()) addView(ctx.secondary(if (prefs.login.isNotBlank()) "$group · ${prefs.login}" else group, 14f))
        })
        val buttons = ctx.horizontal().apply { setPadding(0, ctx.dp(14), 0, 0) }
        val sync = ctx.button("Обновить") {
            main.withPermissions { syncAll() }
        } as com.google.android.material.button.MaterialButton
        sync.apply {
            isEnabled = !loading
            iconGravity = com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = ctx.dp(8)
            icon = if (loading) com.google.android.material.progressindicator.IndeterminateDrawable.createCircularDrawable(
                ctx, com.google.android.material.progressindicator.CircularProgressIndicatorSpec(ctx, null).apply {
                    indicatorSize = ctx.dp(18); trackThickness = ctx.dp(2)
                },
            ) else androidx.core.content.ContextCompat.getDrawable(ctx, R.drawable.ic_refresh)
        }
        buttons.addView(sync.weight())
        buttons.addView(View(ctx).apply { layoutParams = android.widget.LinearLayout.LayoutParams(ctx.dp(10), 1) })
        buttons.addView(ctx.button("Выйти", outlined = true) { confirmReset() }.weight())

        // Итог последнего обновления одной строкой, ошибки отдельно.
        val status = ctx.vertical().apply { setPadding(ctx.dp(2), ctx.dp(10), 0, 0) }
        if (!loading && prefs.lastSyncAt > 0) {
            val n = prefs.lastSyncLessons
            status.addView(ctx.secondary("Обновлено ${whenText(prefs.lastSyncAt)}" +
                if (n > 0) " · ${Remind.plural(n, "пара", "пары", "пар")}" else "", 13f))
        }
        val err = prefs.lastStatus.takeIf { !loading && it.isNotBlank() && !it.startsWith("Синхронизация") }
        if (err != null) status.addView(ctx.text(err, 13f, colorAttr = com.google.android.material.R.attr.colorError).apply {
            setTextIsSelectable(true)
        })
        return ctx.card(row, buttons, status)
    }

    /** «сегодня в 17:17», «вчера в 09:05», «7 ноября в 12:00». */
    private fun whenText(ms: Long): String {
        val t = java.time.Instant.ofEpochMilli(ms).atZone(CalendarSync.SUSU_ZONE)
        val today = java.time.LocalDate.now(CalendarSync.SUSU_ZONE)
        val hm = t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        return when (t.toLocalDate()) {
            today -> "сегодня в $hm"
            today.minusDays(1) -> "вчера в $hm"
            else -> t.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale("ru"))) + " в $hm"
        }
    }

    /** Нажатие на аватарку: своё фото, как на портале или без фото. */
    private fun avatarMenu() {
        val ctx = requireContext()
        val options = buildList {
            add("Выбрать фото" to {
                main.pickPhotos { uris ->
                    val uri = uris.firstOrNull() ?: return@pickPhotos
                    val app = ctx.applicationContext
                    background({ Profile.setCustom(app, uri) }) { render() }
                }
            })
            if (Profile.hasPortalAvatar(ctx)) add("Как на портале" to { Prefs(ctx).avatarMode = Profile.AVATAR_PORTAL; render() })
            add("Без фото" to { Prefs(ctx).avatarMode = Profile.AVATAR_NONE; render() })
        }
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Аватарка")
            .setItems(options.map { it.first }.toTypedArray()) { _, i -> options[i].second() }
            .show()
    }

    private fun confirmReset() {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setTitle("Выйти?")
            .setMessage("С телефона удалятся календари с парами, сохранённые расписания, оценки, история и логин с паролем.")
            .setPositiveButton("Удалить") { _, _ ->
                val app = ctx.applicationContext
                background({
                    Prefs(app).syncIntervalHours = 0
                    SyncWorker.schedule(app)
                    if (Syncer.hasCalendarPermission(app)) CalendarSync(app).deleteAll()
                    Store(app).clearAll()
                    GradesStore(app).clearAll()
                    ChangeLog(app).clear()
                    Planner.clear(app)
                    Profile.clear(app)
                    MyLessons.clear(app)
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
