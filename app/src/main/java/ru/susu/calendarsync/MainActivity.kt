package ru.susu.calendarsync

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.susu.calendarsync.databinding.ActivityMainBinding
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** Расписание, открытое из поиска, но ещё не сохранённое. */
    var tempSchedule: SavedSchedule? = null
    var tempLessons: List<Lesson> = emptyList()
    var selectedUid: String? = null
    /** Расписания, уже обновлённые с момента запуска приложения. */
    val refreshedThisLaunch = HashSet<String>()
    var week: LocalDate = LocalDate.now(CalendarSync.SUSU_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private var afterPermissions: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        afterPermissions?.invoke()
        afterPermissions = null
    }

    private val tabs = listOf(R.id.nav_schedule, R.id.nav_search, R.id.nav_grades, R.id.nav_history, R.id.nav_settings)

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(AppearancePage.nightMode(Prefs(this).display.theme))
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Notifier.ensureChannels(this)
        // Пока открыта клавиатура, нижняя панель вкладок не нужна и только отнимает место.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            b.bottomNav.visibility = if (insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
        }

        b.bottomNav.setOnItemSelectedListener { item ->
            showPage(tabs.indexOf(item.itemId))
            true
        }
        val start = intent.getIntExtra(Notifier.EXTRA_TAB, -1).takeIf { it >= 0 }
            ?: if (Prefs(this).hasCredentials) Syncer.TAB_SCHEDULE else Syncer.TAB_SETTINGS
        if (savedInstanceState == null) {
            showTab(start)
            showPassHint()
            Updater.autoCheck(this, lifecycleScope)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPassSwipe()
    }

    fun refreshPassSwipe() {
        passSwipeEnabled = Prefs(this).let { it.hasCredentials && it.display.passSwipe }
    }

    // ---- Пропуск: смахивание слева направо и подсказка при запуске ----

    private var passSwipeEnabled = false
    private var swipeX = 0f
    private var swipeY = 0f
    private var swipeTracking = false

    fun openPass() = PassTicket.show(this, lifecycleScope)

    /**
     * Горизонтальный жест слева направо в любом месте экрана открывает пропуск.
     * Касания у самого края не отслеживаем — там системный жест «Назад».
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (passSwipeEnabled) when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                swipeX = ev.x
                swipeY = ev.y
                swipeTracking = ev.x > dp(28)
            }
            android.view.MotionEvent.ACTION_MOVE -> if (swipeTracking) {
                val dx = ev.x - swipeX
                val dy = kotlin.math.abs(ev.y - swipeY)
                if (dy > dp(24) && dy > kotlin.math.abs(dx)) swipeTracking = false
                else if (dx > dp(110) && dy < dx * 0.5f) {
                    swipeTracking = false
                    // Отменяем касание для ленты, чтобы она не прокрутилась и ничего не нажалось.
                    val cancel = android.view.MotionEvent.obtain(ev).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    openPass()
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Ярлычок «Пропуск → проведите» у левого края: плавно появляется на 5 секунд при запуске. */
    private fun showPassHint() {
        val prefs = Prefs(this)
        if (!prefs.hasCredentials || !prefs.display.passSwipe || !prefs.display.passHint) return
        val decor = window.decorView as android.widget.FrameLayout
        val hint = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(16), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                val r = dp(18).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                setColor(color(com.google.android.material.R.attr.colorPrimaryContainer))
            }
            elevation = dp(6).toFloat()
            addView(text("Пропуск  →", 15f, bold = true, colorAttr = com.google.android.material.R.attr.colorOnPrimaryContainer))
            addView(text("проведите вправо", 12f, colorAttr = com.google.android.material.R.attr.colorOnPrimaryContainer))
            alpha = 0f
            translationX = -dp(40).toFloat()
            setOnClickListener { openPass() }
        }
        decor.addView(hint, android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL,
        ))
        hint.animate().alpha(1f).translationX(0f).setStartDelay(600).setDuration(350).withEndAction {
            // Лёгкое «подталкивание» вправо — намёк на жест.
            hint.animate().translationX(dp(10).toFloat()).setDuration(300).withEndAction {
                hint.animate().translationX(0f).setDuration(300).start()
            }.start()
        }.start()
        hint.postDelayed({
            hint.animate().alpha(0f).translationX(-dp(40).toFloat()).setDuration(400)
                .withEndAction { decor.removeView(hint) }.start()
        }, 5600)
    }

    fun openAppearance() {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
            .replace(R.id.container, AppearancePage())
            .addToBackStack("appearance")
            .commit()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getIntExtra(Notifier.EXTRA_TAB, -1).takeIf { it >= 0 }?.let(::showTab)
    }

    fun showTab(index: Int) {
        b.bottomNav.selectedItemId = tabs[index]
    }

    private fun showPage(index: Int) {
        val page: Fragment = when (index) {
            Syncer.TAB_SEARCH -> SearchPage()
            Syncer.TAB_GRADES -> GradesPage()
            Syncer.TAB_HISTORY -> HistoryPage()
            Syncer.TAB_SETTINGS -> SettingsPage()
            else -> SchedulePage()
        }
        // При смене вкладки закрываем вложенные экраны (например, «Внешний вид»).
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction().replace(R.id.container, page).commit()
    }

    fun openTemp(s: SavedSchedule, lessons: List<Lesson>) {
        tempSchedule = s
        tempLessons = lessons
        selectedUid = s.uid
        week = LocalDate.now(CalendarSync.SUSU_ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        showTab(Syncer.TAB_SCHEDULE)
    }

    /** Запрашивает доступ к календарю (и уведомлениям), затем выполняет [then]. */
    fun withPermissions(then: () -> Unit) {
        val needed = buildList {
            if (!Syncer.hasCalendarPermission(this@MainActivity)) {
                add(Manifest.permission.READ_CALENDAR)
                add(Manifest.permission.WRITE_CALENDAR)
            }
            if (Build.VERSION.SDK_INT >= 33 && !Notifier.canNotify(this@MainActivity)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) then() else {
            afterPermissions = then
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}

/** Базовая страница: вся разметка строится кодом в [build], [render] перестраивает экран. */
abstract class Page : Fragment() {
    protected val main get() = requireActivity() as MainActivity
    private var root: FrameLayout? = null

    abstract fun build(): View

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        FrameLayout(requireContext()).also { root = it; it.addView(build()) }

    override fun onDestroyView() {
        root = null
        super.onDestroyView()
    }

    /** Перестраивает экран, сохраняя позицию прокрутки; с [fade] новое содержимое плавно проявляется поверх старого. */
    fun render(fade: Boolean = false) {
        val r = root ?: return
        val old = if (r.childCount > 0) r.getChildAt(r.childCount - 1) else null
        val oldY = old?.let { findScroll(it) }?.scrollY ?: 0
        // Решение принимаем до build(): страница может сбросить свой флаг прокрутки во время сборки.
        val keep = keepScroll
        val fresh = build()
        if (fade && old != null) {
            fresh.alpha = 0f
            r.addView(fresh)
            fresh.animate().alpha(1f).setDuration(220).withEndAction { r.removeView(old) }.start()
        } else {
            r.removeAllViews()
            r.addView(fresh)
        }
        if (keep) findScroll(fresh)?.let { s -> s.doOnLayout { s.scrollTo(0, oldY) } }
    }

    /** false — страница сама решает, куда прокрутить после перестройки. */
    protected open val keepScroll get() = true

    private fun findScroll(v: View): android.widget.ScrollView? = when (v) {
        is android.widget.ScrollView -> v
        is ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findScroll(v.getChildAt(it)) }
        else -> null
    }

    /** Запускает [work] в фоне и передаёт результат в UI, если экран ещё жив. */
    fun <T> background(work: suspend () -> T, done: (Result<T>) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { work() } }
            if (root != null) done(result)
        }
    }

    protected fun errorText(e: Throwable) = when (e) {
        is AuthException -> "Ошибка входа: ${e.message}"
        is java.net.UnknownHostException -> "Нет подключения к интернету"
        else -> "Ошибка: ${e.message ?: e.javaClass.simpleName}"
    }
}
