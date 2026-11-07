package ru.susu.calendarsync

import android.app.Dialog
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Поиск групп, преподавателей и аудиторий — полноэкранное окно поверх расписания.
 * Результаты обновляются по мере ввода; выбранный результат передаётся в [onPick].
 */
object SearchDialog {
    private var lastQuery = ""
    private var lastResults: List<ScheduleInfo> = emptyList()


    fun show(activity: MainActivity, scope: LifecycleCoroutineScope, onPick: (ScheduleInfo) -> Unit) {
        val ctx = activity
        val dialog = Dialog(ctx, R.style.Theme_SusuCalendar)
        val handler = Handler(Looper.getMainLooper())
        var pending: Runnable? = null
        var requestNo = 0

        val root = ctx.vertical().apply { setBackgroundColor(ctx.color(com.google.android.material.R.attr.colorSurface)) }
        val top = ctx.horizontal().apply { setPadding(ctx.dp(4), ctx.dp(8), ctx.dp(16), ctx.dp(4)) }
        top.addView(ctx.textButton("‹") { dialog.dismiss() }.apply { textSize = 26f; contentDescription = "Назад" })
        val input = TextInputEditText(ctx).apply {
            setText(lastQuery)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        top.addView(TextInputLayout(ctx).apply {
            hint = "Группа, преподаватель или аудитория"
            endIconMode = TextInputLayout.END_ICON_CLEAR_TEXT
            addView(input)
        }.weight())
        root.addView(top)

        val status = ctx.secondary("").apply { setPadding(ctx.dp(20), ctx.dp(4), ctx.dp(20), ctx.dp(4)) }
        root.addView(status)
        val list = ctx.vertical().apply { setPadding(ctx.dp(16), 0, ctx.dp(16), ctx.dp(24)) }
        root.addView(ctx.scroll(list).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        })

        fun showResults(text: String) {
            status.text = text
            list.removeAllViews()
            val store = Store(ctx)
            for (r in lastResults) {
                val saved = store.find("${r.kind.name}_${r.id}") != null
                list.addView(ctx.card(
                    ctx.secondary(r.kind.label + if (saved) " · сохранено" else ""),
                    ctx.text(r.name, 16f, bold = true),
                    *(if (r.description.isNotBlank()) arrayOf<TextView>(ctx.secondary(r.description)) else emptyArray()),
                    onClick = { dialog.dismiss(); onPick(r) },
                ))
            }
        }

        fun search(q: String) {
            val no = ++requestNo
            status.text = "Поиск…"
            scope.launch {
                val r = withContext(Dispatchers.IO) { runCatching { Session.call(ctx.applicationContext) { SusuApi.search(it, q.replace('ё', 'е').replace('Ё', 'Е')) } } }
                if (no != requestNo || !dialog.isShowing) return@launch // пришёл ответ на устаревший запрос
                r.onSuccess {
                    lastResults = it
                    showResults(if (it.isEmpty()) "Ничего не найдено" else "Найдено: ${it.size}")
                }.onFailure { showResults("Не удалось выполнить поиск: ${it.message}") }
            }
        }

        // Поиск с небольшой задержкой после последнего нажатия.
        fun schedule(text: String, delay: Long = 350) {
            pending?.let(handler::removeCallbacks)
            val q = text.trim()
            lastQuery = q
            if (q.length < 2) {
                requestNo++
                lastResults = emptyList()
                showResults(if (q.isEmpty()) "Начните вводить название группы, фамилию или номер аудитории" else "Введите ещё хотя бы один символ")
                return
            }
            pending = Runnable { search(q) }.also { handler.postDelayed(it, delay) }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = schedule(s?.toString().orEmpty())
        })
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) { schedule(input.text.toString(), 0); true } else false
        }
        showResults(if (lastResults.isEmpty()) "Начните вводить название группы, фамилию или номер аудитории" else "Найдено: ${lastResults.size}")
        // Был запрос, но результатов в памяти нет — повторяем поиск.
        if (lastQuery.length >= 2 && lastResults.isEmpty()) schedule(lastQuery, 0)

        // Окно во весь экран: отступаем от строки состояния сверху и от клавиатуры/навигации снизу.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime(),
            )
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        dialog.setContentView(root)
        dialog.setOnDismissListener { pending?.let(handler::removeCallbacks) }
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setGravity(Gravity.TOP)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        input.requestFocus()
        input.setSelection(input.text?.length ?: 0)
    }
}
