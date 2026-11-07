package ru.susu.calendarsync

import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Поиск расписаний групп, преподавателей и аудиторий: результаты обновляются по мере ввода. */
class SearchPage : Page() {
    private companion object {
        var query = ""
        var results: List<ScheduleInfo> = emptyList()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var requestNo = 0
    private var statusView: TextView? = null
    private var resultsBox: LinearLayout? = null

    // Поле ввода строится один раз, обновляется только список — так не теряются фокус и клавиатура.
    override fun build(): View {
        val ctx = requireContext()
        val page = ctx.vertical()
        val header = ctx.vertical().apply { setPadding(ctx.dp(16), ctx.dp(8), ctx.dp(16), 0) }
        header.addView(ctx.title("Поиск расписания"))

        val input = TextInputEditText(ctx).apply {
            setText(query)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        header.addView(TextInputLayout(ctx).apply {
            hint = "Группа, ФИО преподавателя или аудитория"
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            addView(input)
        })
        statusView = ctx.secondary("").apply { setPadding(0, ctx.dp(8), 0, ctx.dp(4)) }
        header.addView(statusView)
        page.addView(header)

        val box = ctx.vertical().apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(24)) }
        resultsBox = box
        page.addView(ctx.scroll(box).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { schedule(input.text.toString(), 0); true } else false
        }
        showResults(if (results.isEmpty()) "Начните вводить название группы, фамилию или номер аудитории" else "Найдено: ${results.size}")
        return page
    }

    override fun onDestroyView() {
        pending?.let(handler::removeCallbacks)
        statusView = null
        resultsBox = null
        super.onDestroyView()
    }

    /** Запускает поиск с небольшой задержкой после последнего нажатия. */
    private fun schedule(text: String, delayMs: Long = 350) {
        pending?.let(handler::removeCallbacks)
        val q = text.trim()
        if (q == query && results.isNotEmpty() && delayMs > 0) return
        query = q
        if (q.length < 2) {
            requestNo++
            results = emptyList()
            showResults(if (q.isEmpty()) "Начните вводить название группы, фамилию или номер аудитории" else "Введите ещё хотя бы один символ")
            return
        }
        pending = Runnable { search(q) }.also { handler.postDelayed(it, delayMs) }
    }

    private fun search(q: String) {
        val no = ++requestNo
        statusView?.text = "Поиск…"
        val ctx = requireContext().applicationContext
        background({ Session.call(ctx) { SusuApi.search(it, q) } }) { r ->
            if (no != requestNo) return@background // пришёл ответ на устаревший запрос
            r.onSuccess {
                results = it
                showResults(if (it.isEmpty()) "Ничего не найдено" else "Найдено: ${it.size}")
            }.onFailure { showResults(errorText(it)) }
        }
    }

    private fun showResults(status: String) {
        val ctx = context ?: return
        val box = resultsBox ?: return
        statusView?.text = status
        box.removeAllViews()
        val store = Store(ctx)
        for (r in results) {
            val uid = "${r.kind.name}_${r.id}"
            val saved = store.find(uid) != null
            val actions = ctx.horizontal()
            actions.addView(ctx.textButton("Открыть") { open(r) })
            actions.addView(ctx.textButton(if (saved) "✓ Сохранено" else "★ Сохранить") {
                store.upsert(SavedSchedule.from(r))
                showResults("«${r.name}» сохранено. Отслеживание и календарь включаются в «Расписании» → «⚙ Уведомления и календарь»")
            }.apply { isEnabled = !saved })
            box.addView(ctx.card(
                ctx.secondary(r.kind.label),
                ctx.text(r.name, 16f, bold = true),
                *(if (r.description.isNotBlank()) arrayOf(ctx.secondary(r.description)) else emptyArray()),
                actions,
                onClick = { open(r) },
            ))
        }
    }

    private fun open(info: ScheduleInfo) {
        val ctx = requireContext().applicationContext
        val saved = Store(ctx).find("${info.kind.name}_${info.id}")
        if (saved != null) {
            main.selectedUid = saved.uid
            main.showTab(Syncer.TAB_SCHEDULE)
            return
        }
        statusView?.text = "Загрузка расписания «${info.name}»…"
        background({ Session.call(ctx) { SusuApi.schedule(it, info.kind, info.id) } }) { r ->
            r.onSuccess { main.openTemp(SavedSchedule.from(info), it) }
                .onFailure { statusView?.text = errorText(it) }
        }
    }
}
