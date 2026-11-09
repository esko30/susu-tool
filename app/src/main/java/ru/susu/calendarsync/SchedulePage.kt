package ru.susu.calendarsync

import android.graphics.Color
import androidx.core.view.doOnLayout
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import androidx.lifecycle.lifecycleScope
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Просмотр расписания: своё, сохранённые и открытое из поиска.
 * Все занятия идут сплошной лентой по дням, при открытии лента прокручена к сегодняшнему дню.
 */
class SchedulePage : Page() {
    private var status = ""
    private var loading = false
    private var updateFailed = false
    private var selectorView: com.google.android.material.button.MaterialButton? = null
    private var selectorName: CharSequence = ""
    private var loadingBar: View? = null

    /**
     * Статус обновления без перестройки экрана: в кнопке выбора расписания плавно меняется
     * текст («Обновление…» / «Не обновлено» / название), под шапкой появляется бегущая полоска.
     */
    private fun applyStatus(animate: Boolean = true) {
        val sel = selectorView ?: return
        val ctx = sel.context
        val (text, color) = when {
            loading -> "Обновление…" to ctx.color(com.google.android.material.R.attr.colorPrimary)
            updateFailed -> "Не обновлено" to ctx.color(com.google.android.material.R.attr.colorError)
            else -> selectorName to ctx.color(com.google.android.material.R.attr.colorOnSurface)
        }
        val apply = { sel.text = text; sel.setTextColor(color) }
        if (!animate) apply() else if (sel.text != text) {
            sel.animate().alpha(0f).setDuration(120).withEndAction {
                apply()
                sel.animate().alpha(1f).setDuration(160).start()
            }.start()
        }
        loadingBar?.let { b ->
            val show = loading
            if (!animate) b.visibility = if (show) View.VISIBLE else View.INVISIBLE
            else if (show && b.visibility != View.VISIBLE) {
                b.alpha = 0f; b.visibility = View.VISIBLE; b.animate().alpha(1f).setDuration(200).start()
            } else if (!show && b.visibility == View.VISIBLE) {
                b.animate().alpha(0f).setDuration(250).withEndAction { b.visibility = View.INVISIBLE }.start()
            }
        }
    }

    /** Заглушки-карточки с мягкой пульсацией, пока расписание загружается впервые. */
    private fun addSkeleton(feed: LinearLayout) {
        val ctx = feed.context
        repeat(4) { i ->
            val block = ctx.surfaceCard(View(ctx).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(84))
            })
            if (i == 0) block.layoutParams = (block.layoutParams as LinearLayout.LayoutParams).apply { topMargin = ctx.dp(24) }
            android.animation.ObjectAnimator.ofFloat(block, View.ALPHA, 1f, 0.35f).apply {
                duration = 800
                startDelay = i * 120L
                repeatMode = android.animation.ValueAnimator.REVERSE
                repeatCount = android.animation.ValueAnimator.INFINITE
                start()
            }
            feed.addView(block)
        }
    }
    private var jumpToToday = true
    private val autoLoaded = HashSet<String>()

    private val ru = Locale("ru")

    override val keepScroll get() = !jumpToToday
    private var display = DisplayOptions(true, true, true, true, true, true, true, false)
    /** Занятия открытого расписания и записи планера по парам — для карточек и панели «В планер». */
    private var feedLessons: List<Lesson> = emptyList()
    private var plannerByLesson: Map<String, List<PlannerItem>> = emptyMap()


    private fun choices(): List<SavedSchedule> {
        val saved = Store(requireContext()).saved()
        val temp = main.tempSchedule?.takeIf { t -> saved.none { it.uid == t.uid } }
        return if (temp != null) saved + temp else saved
    }

    override fun build(): View {
        val ctx = requireContext()
        live.clear()
        val page = ctx.vertical()
        val header = ctx.vertical(16).apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), ctx.dp(4)) }
        page.addView(header)

        val list = choices()
        if (list.isEmpty() && Prefs(ctx).hasCredentials && "own" !in autoLoaded) {
            // Аккаунт уже есть, но своё расписание ещё не зарегистрировано (например, после обновления приложения).
            autoLoaded += "own"
            val app = ctx.applicationContext
            header.addView(ctx.title("Расписание"))
            header.addView(ctx.secondary("Загрузка…"))
            background({ Syncer.syncSchedule(app, Session.login(app).let { Store(app).own()!! }) }) { r ->
                r.onFailure { status = errorText(it) }
                jumpToToday = true
                render()
            }
            return page
        }
        if (list.isEmpty()) {
            header.addView(ctx.title("Расписание"))
            if (status.isNotBlank()) header.addView(ctx.secondary(status))
            header.addView(ctx.secondary("Войдите в аккаунт на вкладке «Настройки» или найдите группу, преподавателя или аудиторию."))
            header.addView(ctx.button("Найти расписание", outlined = true) {
                SearchDialog.show(main, viewLifecycleOwner.lifecycleScope) { showQuick(it.kind, it.name, it) }
            })
            return page
        }

        val store = Store(ctx)
        val current = list.firstOrNull { it.uid == main.selectedUid } ?: list.first()
        main.selectedUid = current.uid
        val isTemp = main.tempSchedule?.uid == current.uid && store.find(current.uid) == null

        // Шапка — одна строка: выбор расписания + «Сегодня», «Звонки», меню.
        // Она лежит поверх ленты и уезжает вверх при прокрутке вниз.
        val frame = android.widget.FrameLayout(ctx)
        header.setPadding(ctx.dp(12), ctx.dp(6), ctx.dp(4), ctx.dp(6))
        header.setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorSurface))
        page.removeView(header)

        val row = ctx.horizontal()
        val selector = ctx.spinner(list.map { if (it === main.tempSchedule) "🔍 ${it.kind.label}: ${it.name}" else it.label } +
            "＋ Найти и добавить…",
            list.indexOf(current)) { i ->
            if (i == list.size) {
                render() // вернуть название текущего расписания на кнопку
                SearchDialog.show(main, viewLifecycleOwner.lifecycleScope) { showQuick(it.kind, it.name, it) }
                return@spinner
            }
            main.selectedUid = list[i].uid
            status = ""
            updateFailed = false
            jumpToToday = true
            render(fade = true)
        } as com.google.android.material.button.MaterialButton
        selectorView = selector
        selectorName = selector.text
        selector.tag = "tour_selector"
        row.addView(selector.weight())
        row.addView(historyButton())
        lateinit var more: View
        more = iconButton(R.drawable.ic_more, "Ещё") { showMenu(more, current, isTemp, store) }.apply { tag = "tour_more" }
        row.addView(more)
        header.addView(row)
        // Тонкая бегущая полоска под шапкой, пока идёт обновление.
        val bar = com.google.android.material.progressindicator.LinearProgressIndicator(ctx).apply {
            isIndeterminate = true
            trackThickness = ctx.dp(2)
            trackCornerRadius = ctx.dp(1)
            setPadding(ctx.dp(4), ctx.dp(4), ctx.dp(12), 0)
            visibility = View.INVISIBLE
        }
        header.addView(bar)
        loadingBar = bar
        applyStatus(animate = false)

        // Расписание обновляется само при каждом входе в приложение (один раз на запуск).
        if (!isTemp && current.uid !in main.refreshedThisLaunch && !loading) {
            main.refreshedThisLaunch += current.uid
            frame.post { refresh(current, false) }
        }

        // Лента занятий.
        val feed = ctx.vertical().apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(24)) }
        val scroll = ctx.scroll(feed).apply { clipToPadding = false }
        frame.addView(scroll)
        frame.addView(header, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        header.doOnLayout { scroll.setPadding(0, header.height, 0, 0) }
        val pill = todayPill { scrollToToday?.invoke() ?: run { weekOffset = null; jumpToToday = true; render(fade = true) } }
        frame.addView(pill, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(48), android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL,
        ).apply { bottomMargin = ctx.dp(16) })
        var programmatic = false
        var anchorRef: View? = null
        scroll.setOnScrollChangeListener { _, _, y, _, oldY ->
            // Далеко от сегодняшнего дня — показываем «Вернуться к сегодня».
            anchorRef?.let { a ->
                val target = todayOffset(a, feed)
                if (!programmatic) showPill(pill, kotlin.math.abs(y - target) > scroll.height * 0.7f)
            }
            if (programmatic) return@setOnScrollChangeListener
            val h = header.height.toFloat()
            when {
                y - oldY > 6 && y > h -> header.animate().translationY(-h).setDuration(180).start()
                oldY - y > 6 || y <= h -> header.animate().translationY(0f).setDuration(180).start()
            }
        }

        display = Prefs(ctx).display
        val lessons = if (isTemp) main.tempLessons else store.lessons(current.uid)
        feedLessons = lessons
        plannerByLesson = Planner.byLesson(ctx)
        val today = Clock.today()
        if (Prefs(ctx).weekView) {
            scrollToToday = null
            feed.addView(weekGrid(lessons, today))
            // Листаете другие недели — кнопка возвращает к текущей.
            val defaultWeek = Timeline.defaultWeek(lessons, Clock.now())
            if (weekOffset != null && weekOffset != defaultWeek) showPill(pill, true)
            return frame
        }
        val byDay = lessons.distinctBy { it.key }
            .filter { !display.hidePast || it.date >= today.toString() }
            .sortedWith(compareBy({ it.date }, { it.beginTime })).groupBy { it.date }
        if (byDay.isEmpty()) {
            if (loading) addSkeleton(feed)
            else feed.addView(ctx.secondary(if (updateFailed) "Не удалось загрузить расписание. Проверьте интернет." else "Занятий нет.")
                .apply { setPadding(0, ctx.dp(24), 0, 0) })
        }
        var anchor: View? = null
        var tourTagged = false
        val prefsNow = Prefs(ctx)
        val hidden = Hidden.prune(prefsNow.hiddenLessons, today).also { if (it.size != prefsNow.hiddenLessons.size) prefsNow.hiddenLessons = it }
        for ((date, items) in byDay) {
            val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: continue
            val header2 = dayHeader(d, today)
            feed.addView(header2)
            // Якорь — заголовок сегодняшнего дня с меткой «Сегодня», а если сегодня пар нет — ближайшего дня.
            if (anchor == null && !d.isBefore(today)) anchor = header2
            // Скрытые пары не считаются занятыми: вместо них может появиться окно.
            val windows = if (display.windows) Timeline.windows(items.filterNot { Hidden.isHidden(hidden, it) }).associateBy { it.to } else emptyMap()
            val shown = HashSet<String>()
            val nowDt = Clock.now()
            val pastToday = if (d == today && display.collapsePast && !pastExpanded)
                items.filter { bounds(it)?.second?.let { e -> !e.isAfter(nowDt) } == true } else emptyList()
            if (pastToday.isNotEmpty()) feed.addView(pastRow(pastToday.size))
            (items - pastToday.toSet()).forEach { l ->
                windows[l.beginTime]?.takeIf { shown.add(it.to) }?.let { feed.addView(windowRow(it, d, today)) }
                val card = if (Hidden.isHidden(hidden, l)) hiddenStrip(l) else swipeable(l, lessonCard(l, past = d < today))
                // Для тура — первая пара начиная с сегодняшнего дня.
                if (!tourTagged && !d.isBefore(today)) { card.tag = "tour_card"; tourTagged = true }
                feed.addView(card)
            }
        }
        updateLive(allowRender = false)
        // Для кнопки «Сегодня»: плавная прокрутка к якорю без перестройки экрана.
        anchorRef = anchor
        scrollToToday = if (byDay.isEmpty()) null else {
            val target = anchor
            {
                header.animate().translationY(0f).setDuration(180).start()
                showPill(pill, false)
                scroll.smoothScrollTo(0, todayOffset(target, feed))
            }
        }
        if (jumpToToday && byDay.isNotEmpty()) {
            jumpToToday = false
            val target = anchor
            // Прокручиваем только после раскладки ленты, иначе позиции карточек ещё не известны.
            val jump = {
                programmatic = true
                scroll.scrollTo(0, todayOffset(target, feed))
                scroll.post { programmatic = false }
            }
            feed.doOnLayout { jump() }
            // Шапка задаёт отступ ленты после первой раскладки — повторяем прокрутку, когда он применится.
            header.doOnLayout { scroll.post { scroll.post { jump() } } }
        }
        return frame
    }

    private var scrollToToday: (() -> Unit)? = null

    /** Прошедшие пары сегодня раскрыты вручную (до перезапуска экрана). */
    private var pastExpanded = false

    /** «2 пары прошли ▾» вместо карточек закончившихся сегодня пар; нажатие раскрывает их. */
    private fun pastRow(n: Int): View {
        val ctx = requireContext()
        return ctx.horizontal().apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(16), ctx.dp(12), ctx.dp(16), ctx.dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(14).toFloat()
                setColor(ctx.color(com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, ctx.dp(5), 0, ctx.dp(5)) }
            addView(ctx.secondary(Remind.plural(n, "пара прошла", "пары прошли", "пар прошло"), 14f).weight())
            addView(ctx.secondary("Показать ▾", 14f).apply {
                setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary))
            })
            setOnClickListener { pastExpanded = true; render() }
        }
    }

    private fun setHidden(l: Lesson, hide: Boolean) {
        val prefs = Prefs(requireContext())
        prefs.hiddenLessons = if (hide) prefs.hiddenLessons + Hidden.key(l) else prefs.hiddenLessons - Hidden.key(l)
        runCatching { NowWidget.update(requireContext().applicationContext) }
    }

    /** Карточку пары можно смахнуть: она сжимается в полоску, снизу появляется «Вернуть». */
    private fun swipeable(l: Lesson, card: View): View {
        lateinit var row: SwipeRow
        row = SwipeRow(requireContext(), card) {
            setHidden(l, true)
            SwipeRow.collapse(row, requireContext().dp(30)) { render() }
            com.google.android.material.snackbar.Snackbar.make(requireView(), "Пара скрыта", com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
                .setAction("Вернуть") { setHidden(l, false); render() }
                .apply { requireActivity().findViewById<View>(R.id.bottomNav)?.let { setAnchorView(it) } }
                .show()
        }
        return row
    }

    /** Скрытая пара: узкая полоска с бледным зачёркнутым глазом. Нажатие или свайп возвращают её. */
    private fun hiddenStrip(l: Lesson): View {
        val ctx = requireContext()
        val strip = android.widget.FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dp(15).toFloat()
                setColor(ctx.color(com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(30))
                .apply { setMargins(0, ctx.dp(5), 0, ctx.dp(5)) }
            contentDescription = "Скрытая пара ${l.beginTime} ${l.subject}. Нажмите, чтобы показать"
            addView(android.widget.ImageView(ctx).apply {
                setImageResource(R.drawable.ic_eye_off)
                setColorFilter(ctx.color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                alpha = 0.4f
            }, android.widget.FrameLayout.LayoutParams(ctx.dp(17), ctx.dp(17), android.view.Gravity.CENTER))
            setOnClickListener { setHidden(l, false); render() }
        }
        return SwipeRow(ctx, strip) { setHidden(l, false); render() }
    }

    /** Сдвиг показанной недели от текущей (вид «неделя»). */
    /** null — неделя по умолчанию: текущая, а после её последней пары — следующая. */
    private var weekOffset: Long? = null

    /**
     * Неделя сеткой: строки — пары по звонкам, столбцы — дни. В ячейке — сокращённый предмет и аудитория,
     * цвет — по типу занятия. Нажатие открывает полные карточки пар этой ячейки.
     */
    private fun weekGrid(lessons: List<Lesson>, today: LocalDate): View {
        val ctx = requireContext()
        val box = ctx.vertical()
        val offset = weekOffset ?: Timeline.defaultWeek(lessons, Clock.now())
        val monday = Timeline.weekStart(today).plusWeeks(offset)
        val days = (0L until 7L).map { monday.plusDays(it) }
        val inWeek = lessons.distinctBy { it.key }.filter { it.date >= monday.toString() && it.date <= days.last().toString() }
        // Воскресенье — только если в него есть пары.
        val shownDays = days.filter { it.dayOfWeek != java.time.DayOfWeek.SUNDAY || inWeek.any { l -> l.date == it.toString() } }

        // Переключатель недель.
        val nav = ctx.horizontal().apply { gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(0, ctx.dp(10), 0, ctx.dp(8)) }
        nav.addView(ctx.textButton("‹") { weekOffset = offset - 1; render(fade = true) })
        val f = DateTimeFormatter.ofPattern("d MMMM", ru)
        val title = if (monday.month == days[5].month) "${monday.dayOfMonth}–${days[5].format(f)}"
            else "${monday.format(f)} – ${days[5].format(f)}"
        nav.addView(ctx.vertical().apply {
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(ctx.text(title, 17f, bold = true))
            addView(ctx.secondary(when (offset) {
                0L -> "эта неделя"
                1L -> "следующая неделя"
                -1L -> "прошлая неделя"
                else -> if (offset > 0) "через $offset нед." else "${-offset} нед. назад"
            }, 12f))
        })
        nav.addView(ctx.textButton("›") { weekOffset = offset + 1; render(fade = true) })
        box.addView(nav)

        if (inWeek.isEmpty()) {
            box.addView(ctx.secondary("На этой неделе занятий нет.").apply { setPadding(ctx.dp(4), ctx.dp(16), 0, 0) })
            return box
        }

        val byCell = inWeek.groupBy { it.date to Timeline.pairNumber(it.beginTime) }
        val numbers = byCell.keys.map { it.second }
        val rows = (numbers.min()..numbers.max()).toList()
        val gap = ctx.dp(3)
        val cellH = ctx.dp(if (display.compact) 58 else 70)

        // Заголовок: дни недели.
        val head = ctx.horizontal()
        head.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(30), 1) })
        for (d in shownDays) {
            val isToday = d == today
            val c = if (isToday) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
            head.addView(ctx.vertical().apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(ctx.text(d.format(DateTimeFormatter.ofPattern("EE", ru)).replaceFirstChar { it.uppercase() }, 12f, bold = true, colorAttr = c))
                addView(ctx.text("${d.dayOfMonth}", 15f, bold = isToday, colorAttr = c))
                setPadding(0, 0, 0, ctx.dp(6))
            })
        }
        box.addView(head)

        val now = Clock.now()
        for (n in rows) {
            val row = ctx.horizontal().apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, cellH).apply { bottomMargin = gap }
            }
            val bell = Bells.pairs.firstOrNull { it.number == n }
            row.addView(ctx.vertical().apply {
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(ctx.dp(30), ViewGroup.LayoutParams.MATCH_PARENT)
                addView(ctx.text("$n", 14f, bold = true))
                bell?.let { addView(ctx.secondary(it.start1.toString(), 9f)) }
            })
            for (d in shownDays) {
                val cell = byCell[d.toString() to n].orEmpty().sortedBy { it.beginTime }
                row.addView(weekCell(cell, d, today, now).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                        marginStart = gap / 2; marginEnd = gap / 2
                    }
                })
            }
            box.addView(row)
        }
        box.addView(ctx.secondary("Нажмите на ячейку, чтобы открыть пару.", 12f).apply { setPadding(ctx.dp(4), ctx.dp(8), 0, 0) })
        return box
    }

    /** Сокращение предмета для ячейки сетки: «Математический анализ» → «Мате. анализ». */
    private fun shortSubject(s: String): String {
        val words = s.split(' ').filter { it.isNotBlank() }
        if (s.length <= 14 || words.size < 2) return s
        return words.joinToString(" ") { w -> if (w.length > 7 && w.first().isLetter()) w.take(4) + "." else w }
    }

    private fun weekCell(cell: List<Lesson>, d: LocalDate, today: LocalDate, now: LocalDateTime): View {
        val ctx = requireContext()
        val v = ctx.vertical().apply { setPadding(ctx.dp(4), ctx.dp(3), ctx.dp(3), ctx.dp(3)) }
        val r = ctx.dp(8).toFloat()
        if (cell.isEmpty()) {
            v.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = r
                setColor(ctx.color(com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            if (d < today) v.alpha = 0.6f
            return v
        }
        val l = cell.first()
        val accent = typeColor(l.eventType)
        val b = bounds(l)
        val ongoing = b != null && !now.isBefore(b.first) && now.isBefore(b.second)
        v.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = r
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(accent, 0x38))
            if (ongoing) setStroke(ctx.dp(2), accent)
        }
        v.addView(ctx.text(shortSubject(l.subject), 10f, bold = true).apply {
            maxLines = if (this@SchedulePage.display.compact) 2 else 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            setLineSpacing(0f, 0.9f)
        })
        val room = l.location.substringBefore(";").trim()
        if (room.isNotBlank()) v.addView(ctx.text(room, 9f, bold = true).apply {
            setTextColor(ctx.readable(accent)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (cell.size > 1) v.addView(ctx.secondary("+${cell.size - 1}", 9f))
        // Прошедшие — чуть бледнее, но читаемые.
        if (d < today || (b != null && b.second.isBefore(now))) v.alpha = 0.72f
        v.setOnClickListener { showCell(cell, d < today) }
        return v
    }

    /** Полные карточки пар ячейки — с теми же нажатиями, что и в ленте. */
    private fun showCell(cell: List<Lesson>, past: Boolean) {
        val ctx = requireContext()
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val box = ctx.vertical(16)
        box.addView(ctx.secondary(ChangeDiff.day(cell.first().date).replaceFirstChar { it.uppercase() }, 14f).apply {
            setPadding(ctx.dp(4), 0, 0, ctx.dp(4))
        })
        cell.forEach { box.addView(lessonCard(it, past)) }
        sheet.setContentView(ctx.scroll(box))
        sheet.show()
    }

    /** Плашка свободного окна между парами; сегодняшнее идущее окно — с отсчётом. */
    private fun windowRow(w: Timeline.Window, d: LocalDate, today: LocalDate): View {
        val ctx = requireContext()
        val row = ctx.horizontal().apply {
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(8), ctx.dp(2), ctx.dp(8), ctx.dp(6))
            if (d < today) alpha = 0.55f
        }
        fun line() = View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, ctx.dp(1), 1f)
            setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorOutlineVariant))
        }
        val now = Clock.now()
        val active = d == today && now.toLocalTime().let { it >= java.time.LocalTime.parse(w.from) && it < java.time.LocalTime.parse(w.to) }
        val left = if (active) Duration.between(now.toLocalTime(), java.time.LocalTime.parse(w.to)).toMinutes() else 0
        val label = "Окно ${Timeline.duration(w.minutes)} · ${w.from}–${w.to}" +
            if (active) " · ещё ${Timeline.duration(left.coerceAtLeast(1))}" else ""
        row.addView(line())
        row.addView(ctx.text(label, 12f, bold = active,
            colorAttr = if (active) com.google.android.material.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant,
        ).apply { setPadding(ctx.dp(10), 0, ctx.dp(10), 0) })
        row.addView(line())
        return row
    }

    /** Позиция прокрутки, при которой якорный день стоит сразу под шапкой; без якоря — конец ленты. */
    private fun todayOffset(target: View?, feed: View) =
        target?.let { (it.top + it.paddingTop - feed.context.dp(14)).coerceAtLeast(0) } ?: feed.height

    /** Колокольчик «Изменения» со счётчиком новых изменений с прошлого просмотра. */
    private fun showBells() {
        val ctx = requireContext()
        val box = ctx.vertical(20)
        val now = Clock.now().toLocalTime()
        for (p in Bells.pairs) {
            val row = ctx.horizontal().apply { setPadding(0, ctx.dp(6), 0, ctx.dp(6)) }
            val current = !now.isBefore(p.start1) && now.isBefore(p.end2)
            row.addView(ctx.text("${p.number}", 18f, bold = true).apply {
                width = ctx.dp(36)
                if (current) setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary))
            })
            row.addView(ctx.text(p.text, 16f, bold = current).apply {
                if (current) setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary))
            })
            box.addView(row)
        }
        MaterialAlertDialogBuilder(ctx).setTitle("Расписание звонков").setView(box).setPositiveButton("OK", null).show()
    }

    /** Плавающая кнопка «Вернуться к сегодня»: появляется, когда лента ушла от сегодняшнего дня. */
    private fun todayPill(onClick: () -> Unit) = com.google.android.material.button.MaterialButton(requireContext()).apply {
        val ctx = requireContext()
        text = "Вернуться к сегодня"
        isAllCaps = false
        cornerRadius = ctx.dp(24)
        elevation = ctx.dp(6).toFloat()
        setPadding(ctx.dp(20), 0, ctx.dp(20), 0)
        visibility = View.GONE
        alpha = 0f
        setOnClickListener { onClick() }
    }

    private fun showPill(pill: View, show: Boolean) {
        if (show == (pill.visibility == View.VISIBLE && pill.alpha > 0f)) return
        if (show) {
            pill.visibility = View.VISIBLE
            pill.translationY = pill.context.dp(24).toFloat()
            pill.animate().alpha(1f).translationY(0f).setDuration(200).start()
        } else {
            pill.animate().alpha(0f).translationY(pill.context.dp(24).toFloat()).setDuration(160)
                .withEndAction { pill.visibility = View.GONE }.start()
        }
    }

    /** Сколько изменений появилось с прошлого просмотра «Изменений». */
    private fun unseenChanges(): Int {
        val ctx = requireContext()
        val seen = Prefs(ctx).historySeen
        return ChangeLog(ctx).load().filter { it.timestamp > seen }.sumOf { it.changes.size }
    }

    /** Колокольчик «Изменения» со счётчиком новых изменений с прошлого просмотра. */
    private fun historyButton(): View {
        val ctx = requireContext()
        val unseen = unseenChanges()
        return android.widget.FrameLayout(ctx).apply {
            tag = "tour_history"
            addView(iconButton(R.drawable.ic_bell, "Изменения") { main.openHistory() })
            if (unseen > 0) addView(TextView(ctx).apply {
                text = if (unseen > 99) "99+" else unseen.toString()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(ctx.color(com.google.android.material.R.attr.colorOnError))
                gravity = android.view.Gravity.CENTER
                minWidth = ctx.dp(16)
                setPadding(ctx.dp(4), 0, ctx.dp(4), 0)
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ctx.dp(8).toFloat()
                    setColor(ctx.color(com.google.android.material.R.attr.colorError))
                }
            }, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ctx.dp(16), android.view.Gravity.TOP or android.view.Gravity.END,
            ).apply { setMargins(0, ctx.dp(6), ctx.dp(4), 0) })
        }
    }

    private fun iconButton(icon: Int, description: String, onClick: () -> Unit) =
        com.google.android.material.button.MaterialButton(
            requireContext(), null, com.google.android.material.R.attr.materialIconButtonStyle,
        ).apply {
            setIconResource(icon)
            contentDescription = description
            iconTint = android.content.res.ColorStateList.valueOf(
                requireContext().color(com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            setOnClickListener { onClick() }
        }

    /** Меню «⋮»: обновить, уведомления и календарь, сохранить/удалить. */
    private fun showMenu(anchor: View, current: SavedSchedule, isTemp: Boolean, store: Store) {
        val ctx = requireContext()
        val popup = android.widget.PopupMenu(ctx, anchor)
        popup.menu.add(0, 1, 0, "Обновить")
        popup.menu.add(0, 5, 0, "Расписание звонков")
        popup.menu.add(0, 6, 0, if (Prefs(ctx).weekView) "Вид: лента" else "Вид: неделя")
        if (isTemp) popup.menu.add(0, 2, 1, "★ Сохранить расписание")
        else popup.menu.add(0, 3, 1, "Уведомления и календарь…")
        if (!isTemp && !current.own) popup.menu.add(0, 4, 2, "Удалить из сохранённых")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> refresh(current, isTemp)
                5 -> showBells()
                6 -> {
                    val p = Prefs(ctx)
                    p.weekView = !p.weekView
                    weekOffset = null
                    jumpToToday = true
                    render(fade = true)
                }
                2 -> {
                    store.upsert(current)
                    store.saveLessons(current.uid, main.tempLessons)
                    main.tempSchedule = null
                    status = "сохранено"
                    render()
                    showOptionsDialog(store.find(current.uid) ?: current, store)
                }
                3 -> showOptionsDialog(store.find(current.uid) ?: current, store)
                4 -> confirmDelete(current, store)
            }
            true
        }
        popup.show()
    }

    private fun showOptionsDialog(current: SavedSchedule, store: Store) {
        val ctx = requireContext()
        val box = ctx.vertical(20)
        addOptions(box, current, store)
        MaterialAlertDialogBuilder(ctx).setTitle(current.label).setView(box).setPositiveButton("Готово", null).show()
    }

    private fun addOptions(header: LinearLayout, current: SavedSchedule, store: Store) {
        val ctx = requireContext()
        fun update(f: (SavedSchedule) -> SavedSchedule) = store.find(current.uid)?.let { store.upsert(f(it)) }
        header.addView(ctx.switch("Следить за изменениями и уведомлять", current.monitor) { v ->
            update { it.copy(monitor = v) }
        })
        header.addView(ctx.switch("Добавлять в календарь телефона", current.toCalendar) { v ->
            update { it.copy(toCalendar = v) }
            if (v) main.withPermissions { refresh(current.copy(toCalendar = true), false) }
            else background({
                if (Syncer.hasCalendarPermission(ctx)) CalendarSync(ctx).deleteCalendar(current.calendarKey)
            }) { status = "Календарь «${current.calendarTitle}» удалён"; render() }
        })
    }

    private fun confirmDelete(current: SavedSchedule, store: Store) {
        val ctx = requireContext()
        MaterialAlertDialogBuilder(ctx)
            .setMessage("Удалить «${current.label}» из сохранённых?" +
                if (current.toCalendar) " Его календарь на телефоне тоже будет удалён." else "")
            .setPositiveButton("Удалить") { _, _ ->
                store.remove(current.uid)
                main.selectedUid = null
                background({
                    if (Syncer.hasCalendarPermission(ctx)) CalendarSync(ctx).deleteCalendar(current.calendarKey)
                }) { jumpToToday = true; render() }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    /** Сегодняшняя пара, у которой на экране показывается живой статус. */
    private class Live(val lesson: Lesson, val state: Int, val label: TextView, val bar: GlowProgress?)

    private val live = ArrayList<Live>()
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            updateLive()
            ticker.postDelayed(this, 15_000)
        }
    }

    override fun onResume() {
        super.onResume()
        ticker.post(tick)
    }

    override fun onPause() {
        ticker.removeCallbacks(tick)
        super.onPause()
    }

    private fun bounds(l: Lesson): Pair<LocalDateTime, LocalDateTime>? = runCatching {
        LocalDateTime.parse("${l.date}T${l.beginTime}") to LocalDateTime.parse("${l.date}T${l.endTime}")
    }.getOrNull()

    /** 0 — ещё не началась, 1 — идёт, 2 — закончилась. */
    private fun state(l: Lesson, now: LocalDateTime): Int {
        val (start, end) = bounds(l) ?: return 0
        return when {
            now.isBefore(start) -> 0
            now.isBefore(end) -> 1
            else -> 2
        }
    }

    private fun minutesText(min: Long) = if (min >= 60) "${min / 60} ч ${"%02d".format(min % 60)} мин" else "$min мин"

    /** Обновляет полосы прогресса; если какая-то пара началась или закончилась — перестраивает экран. */
    private fun updateLive(allowRender: Boolean = true) {
        if (live.isEmpty()) return
        val now = Clock.now()
        if (allowRender && live.any { state(it.lesson, now) != it.state }) {
            jumpToToday = false
            render()
            return
        }
        for (item in live) {
            val (start, end) = bounds(item.lesson) ?: continue
            when (item.state) {
                0 -> item.label.text = "Начнётся через ${minutesText(Duration.between(now, start).toMinutes() + 1)}"
                1 -> {
                    val total = Duration.between(start, end).seconds.coerceAtLeast(1)
                    val passed = Duration.between(start, now).seconds
                    item.bar?.progress = passed.toFloat() / total
                    val left = "ещё ${minutesText(Duration.between(now, end).toMinutes() + 1)}"
                    item.label.text = "Идёт · $left"
                }
            }
        }
    }

    /** Заголовок дня: «Суббота» крупно, дата мельче, у сегодняшнего — метка «Сегодня». */
    private fun dayHeader(d: LocalDate, today: LocalDate): View {
        val ctx = requireContext()
        val row = ctx.horizontal().apply { setPadding(ctx.dp(4), ctx.dp(22), 0, ctx.dp(6)) }
        val weekday = d.format(DateTimeFormatter.ofPattern("EEEE", ru)).replaceFirstChar { it.uppercase() }
        row.addView(ctx.text(weekday, 20f, bold = true).apply {
            if (d == today) setTextColor(ctx.color(com.google.android.material.R.attr.colorPrimary))
        })
        row.addView(ctx.secondary("  " + d.format(DateTimeFormatter.ofPattern("d MMMM", ru)), 15f))
        if (d == today) {
            row.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(10), 1) })
            row.addView(ctx.chip("Сегодня", ctx.color(com.google.android.material.R.attr.colorPrimary), filled = true))
        }
        if (d < today) row.alpha = 0.55f
        return row
    }

    /** Цвет полосы и метки по типу занятия. */
    private fun typeColor(type: String): Int = lessonTypeColor(type)


    /** Убирает служебные подписи: ФИО и номера групп понятны и так. */
    private fun stripLabel(line: String) = line.substringAfter(": ", line)

    /** «Батурина Наталья Владимировна» → «Батурина Н. В.». */
    private fun shortName(full: String): String {
        val p = full.trim().split(Regex("\\s+"))
        if (p.size < 2) return full
        return p.first() + " " + p.drop(1).joinToString(" ") { it.first().uppercase() + "." }
    }

    private fun lessonCard(l: Lesson, past: Boolean): View {
        val ctx = requireContext()
        val accentColor = typeColor(l.eventType)

        // Левая колонка: номер пары и время.
        val timeCol = ctx.vertical().apply {
            // Фиксированная ширина, чтобы названия у всех карточек начинались ровно.
            layoutParams = LinearLayout.LayoutParams(ctx.dp(66), ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { setMargins(0, 0, ctx.dp(10), 0) }
            // this@SchedulePage — иначе внутри apply «display» указывает на View.getDisplay().
            if (this@SchedulePage.display.pairNumber) Bells.find(l.beginTime)?.let { addView(ctx.secondary("${it.number} пара", 12f)) }
            addView(ctx.text(l.beginTime, 17f, bold = true))
            addView(ctx.secondary(l.endTime, 14f))
        }
        // Тип занятия — лёгкий лейбл-рамка в нижнем левом углу карточки: ЛЕК, СЕМ, ЛАБ…
        // Под него в колонке времени оставлено место, чтобы он ничего не перекрывал.
        val tagText = if (display.typeTag) DisplayOptions.typeTag(l.eventType) else ""
        val tagView = if (tagText.isBlank()) null else ctx.outlineTag(tagText, accentColor)
        if (tagView != null) timeCol.setPadding(0, 0, 0, ctx.dp(34))
        // Компактный режим — меньше отступы внутри карточки.
        val pad = if (display.compact) 10 else 14

        // Правая колонка: предмет, тип, аудитория, преподаватель, группы.
        val content = ctx.vertical().apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val titleRow = ctx.horizontal().apply { gravity = android.view.Gravity.TOP }
        val mini = display.mini
        titleRow.addView(ctx.text(l.subject, if (mini) 14f else 15f, bold = true).apply {
            // Длинные слова переносим по слогам с дефисом, а не рвём посередине.
            hyphenationFrequency = android.text.Layout.HYPHENATION_FREQUENCY_NORMAL
            breakStrategy = android.text.Layout.BREAK_STRATEGY_HIGH_QUALITY
            if (mini) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        }.weight())
        if (display.bigRoom && l.location.isNotBlank()) {
            titleRow.addView(ctx.text(l.location, if (mini) 18f else 22f, bold = true).apply {
                setPadding(ctx.dp(10), 0, 0, 0)
                gravity = android.view.Gravity.END
                clickable { showQuick(ScheduleKind.ROOM, l.location.substringBefore(";").trim()) }
            })
        }
        content.addView(titleRow)

        // Метки планера на этой паре: «Контрольная», «Коллоквиум»… — нажатие открывает запись.
        plannerByLesson["${l.date}|${l.beginTime}|${l.subject}"]?.filter { !it.done }?.takeIf { it.isNotEmpty() }?.let { items ->
            val chips = com.google.android.material.chip.ChipGroup(ctx).apply {
                chipSpacingVertical = ctx.dp(4)
                setPadding(0, ctx.dp(6), 0, ctx.dp(2))
            }
            for (item in items) {
                val title = item.label.ifBlank { "Заметка" } + if (item.note.isNotBlank() && item.label.isNotBlank()) " · …" else ""
                chips.addView(ctx.chip(title, Planner.labelColor(item.label)).apply {
                    setOnClickListener { PlannerSheet.show(ctx, l, item, feedLessons) { render() } }
                })
            }
            content.addView(chips)
        }

        val tags = ctx.horizontal().apply { setPadding(0, ctx.dp(6), 0, ctx.dp(2)) }
        if (!display.bigRoom && l.location.isNotBlank()) {
            tags.addView(ctx.chip(l.location, ctx.color(com.google.android.material.R.attr.colorOnSurfaceVariant)).apply {
                clickable { showQuick(ScheduleKind.ROOM, l.location.substringBefore(";").trim()) }
            })
        }
        if (tags.childCount > 0) content.addView(tags)

        for (line in l.details.lines()) {
            when {
                line.isBlank() || line.startsWith("Тип:") -> Unit
                line.startsWith("Преподавател") -> if (display.teacher) {
                    // Каждый преподаватель отдельной строкой — по нажатию открывается его расписание.
                    for (name in stripLabel(line).split(", ").filter { it.isNotBlank() }) {
                        content.addView(ctx.secondary(if (mini) shortName(name) else name, if (mini) 13f else 14f).apply {
                            if (mini) { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
                            setPadding(0, ctx.dp(2), 0, 0)
                            clickable { showQuick(ScheduleKind.TEACHER, name) }
                        })
                    }
                }
                line.startsWith("Группы") -> if (display.groups) {
                    // Каждая группа нажимается отдельно и открывает своё расписание; строка переносится по ширине.
                    val groups = com.google.android.material.chip.ChipGroup(ctx).apply {
                        chipSpacingHorizontal = ctx.dp(2)
                        chipSpacingVertical = 0
                        setPadding(0, ctx.dp(2), 0, 0)
                    }
                    val names = stripLabel(line).split(", ").filter { it.isNotBlank() }
                    names.forEachIndexed { i, g ->
                        groups.addView(ctx.secondary(if (i < names.lastIndex) "$g," else g, 14f).apply {
                            clickable { showQuick(ScheduleKind.GROUP, g) }
                        })
                    }
                    content.addView(groups)
                }
                else -> content.addView(ctx.secondary(line, 14f).apply { setPadding(0, ctx.dp(2), 0, 0) })
            }
        }
        val views = mutableListOf<View>()

        var ongoing = false
        var done = past
        val now = Clock.now()
        if (l.date == now.toLocalDate().toString()) {
            val st = state(l, now)
            val primary = ctx.color(com.google.android.material.R.attr.colorPrimary)
            when (st) {
                1 -> {
                    ongoing = true
                    val label = ctx.text("", 13f, bold = true).apply { setTextColor(primary) }
                    if (display.progress) {
                        // Прогресс-бар идущей пары; с включённой анимацией — «живой», как подсветка PS5.
                        val bar = GlowProgress(
                            ctx, primary,
                            ctx.color(com.google.android.material.R.attr.colorSurfaceContainerHighest),
                            animated = display.glow,
                        ).apply {
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                                .apply { setMargins(0, ctx.dp(4), 0, 0) }
                        }
                        views += bar
                        views += label
                        live += Live(l, st, label, bar)
                    } else live += Live(l, st, TextView(ctx), null)
                }
                0 -> {
                    val label = ctx.secondary("").apply { setPadding(0, ctx.dp(6), 0, 0) }
                    if (display.countdown) views += label
                    live += Live(l, st, label, null)
                }
                else -> {
                    done = true
                    views += ctx.secondary("Закончилась").apply { setPadding(0, ctx.dp(6), 0, 0) }
                    live += Live(l, st, TextView(ctx), null)
                }
            }
        }
        views.forEach(content::addView)

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
            setPadding(ctx.dp(16), ctx.dp(pad), ctx.dp(16), ctx.dp(pad))
            addView(timeCol)
            addView(content)
            // Лёгкий градиент цвета типа занятия от левого края, растворяется к середине карточки.
            if (this@SchedulePage.display.gradient) background = android.graphics.drawable.ShapeDrawable(android.graphics.drawable.shapes.RectShape()).apply {
                shaderFactory = object : android.graphics.drawable.ShapeDrawable.ShaderFactory() {
                    override fun resize(width: Int, height: Int) = android.graphics.LinearGradient(
                        0f, 0f, width.toFloat(), 0f,
                        intArrayOf(androidx.core.graphics.ColorUtils.setAlphaComponent(accentColor, 56), Color.TRANSPARENT),
                        floatArrayOf(0f, 0.45f), android.graphics.Shader.TileMode.CLAMP,
                    )
                }
            }
        }
        val body = android.widget.FrameLayout(ctx).apply {
            addView(row)
            tagView?.let {
                addView(it, android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.Gravity.BOTTOM or android.view.Gravity.START,
                ).apply { setMargins(ctx.dp(16), 0, 0, ctx.dp(pad)) })
            }
        }
        // Нажатие на карточку — добавить запись в планер к этой (или следующей) паре.
        return ctx.surfaceCard(body) { PlannerSheet.show(ctx, l, null, feedLessons) { render() } }.apply {
            clipToOutline = true
            if (done) alpha = 0.55f
            // Без анимации полоски идущую пару выделяем рамкой.
            if (ongoing && !this@SchedulePage.display.glow) {
                strokeWidth = ctx.dp(2)
                strokeColor = ctx.color(com.google.android.material.R.attr.colorPrimary)
            }
        }
    }

    /** Делает текст нажимаемым с подсветкой нажатия. */
    private fun View.clickable(onClick: () -> Unit) {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        setBackgroundResource(out.resourceId)
        setOnClickListener { onClick() }
    }

    /**
     * Нижняя панель с ближайшим расписанием группы, аудитории или преподавателя.
     * Если [known] не передан, расписание ищется по названию через поиск портала.
     */
    fun showQuick(kind: ScheduleKind, query: String, known: ScheduleInfo? = null) {
        val ctx = requireContext()
        val app = ctx.applicationContext
        val sheet = com.google.android.material.bottomsheet.BottomSheetDialog(ctx)
        val box = ctx.vertical(20)
        box.addView(ctx.secondary(kind.label))
        box.addView(ctx.text(query, 22f, bold = true))
        val body = ctx.vertical().apply { setPadding(0, ctx.dp(12), 0, 0) }
        body.addView(ctx.secondary("Загрузка…"))
        box.addView(body)
        sheet.setContentView(ctx.scroll(box))
        sheet.show()

        background({
            Session.call(app) { token ->
                val found = if (known != null) listOf(known) else SusuApi.search(token, query).filter { it.kind == kind }
                val info = found.firstOrNull { it.name.equals(query, ignoreCase = true) }
                    ?: found.firstOrNull { it.name.startsWith(query, ignoreCase = true) }
                    ?: found.firstOrNull()
                    ?: throw java.io.IOException("Расписание «$query» не найдено")
                info to SusuApi.schedule(token, info.kind, info.id)
            }
        }) { r ->
            body.removeAllViews()
            r.onFailure { body.addView(ctx.secondary(errorText(it))) }
            r.onSuccess { (info, lessons) ->
                if (info.description.isNotBlank()) body.addView(ctx.secondary(info.description))
                val saved = Store(app).find("${info.kind.name}_${info.id}") != null
                val actions = ctx.horizontal().apply { setPadding(0, ctx.dp(12), 0, ctx.dp(4)) }
                actions.addView(ctx.button("Открыть") {
                    sheet.dismiss()
                    if (saved) { main.selectedUid = "${info.kind.name}_${info.id}"; jumpToToday = true; render() }
                    else main.openTemp(SavedSchedule.from(info), lessons)
                }.weight())
                actions.addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ctx.dp(8), 1) })
                actions.addView(ctx.button(if (saved) "✓ Сохранено" else "★ Сохранить", outlined = true) {
                    Store(app).upsert(SavedSchedule.from(info))
                    Store(app).saveLessons("${info.kind.name}_${info.id}", lessons)
                    sheet.dismiss()
                    render()
                }.apply { isEnabled = !saved }.weight())
                body.addView(actions)
                val today = Clock.today()
                val upcoming = lessons.distinctBy { it.key }
                    .filter { it.date >= today.toString() && it.date <= today.plusDays(13).toString() }
                    .sortedWith(compareBy({ it.date }, { it.beginTime })).groupBy { it.date }
                if (upcoming.isEmpty()) body.addView(ctx.secondary("В ближайшие две недели занятий нет.").apply { setPadding(0, ctx.dp(12), 0, 0) })
                for ((date, items) in upcoming) {
                    val d = LocalDate.parse(date)
                    body.addView(ctx.text(
                        d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)).replaceFirstChar { it.uppercase() } +
                            if (d == today) " · сегодня" else "", 15f, bold = true,
                    ).apply { setPadding(0, ctx.dp(14), 0, ctx.dp(4)) })
                    for (l in items) {
                        val line = ctx.horizontal().apply { setPadding(0, ctx.dp(4), 0, ctx.dp(4)); gravity = android.view.Gravity.TOP }
                        line.addView(ctx.text(l.beginTime, 14f, bold = true).apply { width = ctx.dp(52) })
                        val col = ctx.vertical().apply { layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
                        col.addView(ctx.text(l.subject, 14f))
                        // Для аудитории — преподаватель и группы, для преподавателя — группы, для группы — преподаватель.
                        val wanted = when (kind) {
                            ScheduleKind.ROOM -> listOf("Преподавател", "Группы")
                            ScheduleKind.TEACHER -> listOf("Группы")
                            ScheduleKind.GROUP -> listOf("Преподавател")
                        }
                        val meta = listOf(display.typeWord(l.eventType), if (kind == ScheduleKind.ROOM) "" else l.location) +
                            l.details.lines().filter { line -> wanted.any { line.startsWith(it) } }.map(::stripLabel)
                        col.addView(ctx.secondary(meta.filter { it.isNotBlank() }.joinToString(" · "), 12f))
                        line.addView(col)
                        body.addView(line)
                    }
                }
            }
        }
    }

    private fun refresh(s: SavedSchedule, isTemp: Boolean) {
        val ctx = requireContext().applicationContext
        // Что сейчас на экране — чтобы после загрузки перестраивать ленту только при реальных изменениях.
        val before = if (isTemp) main.tempLessons else Store(ctx).lessons(s.uid)
        loading = true
        status = ""
        updateFailed = false
        // Пустую ленту сразу заменяем заглушками; иначе только меняем статус в шапке, лента остаётся.
        if (before.isEmpty()) render(fade = true) else applyStatus()
        background({
            if (isTemp) Session.call(ctx) { SusuApi.schedule(it, s.kind, s.id) }
            else Syncer.syncSchedule(ctx, Store(ctx).find(s.uid) ?: s)
        }) { r ->
            loading = false
            r.onSuccess { lessons ->
                if (isTemp) main.tempLessons = lessons
                if (lessons != before) render(fade = true) else applyStatus()
            }.onFailure {
                // Сбой: остаётся сохранённое расписание, в шапке на несколько секунд — «Не обновлено».
                status = errorText(it)
                updateFailed = true
                if (before.isEmpty()) render(fade = true) else applyStatus()
                ticker.postDelayed({
                    if (updateFailed) { updateFailed = false; applyStatus() }
                }, 5000)
            }
        }
    }
}
