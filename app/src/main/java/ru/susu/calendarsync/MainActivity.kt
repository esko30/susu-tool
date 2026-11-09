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

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding

    /** Расписание, открытое из поиска, но ещё не сохранённое. */
    var tempSchedule: SavedSchedule? = null
    var tempLessons: List<Lesson> = emptyList()
    var selectedUid: String? = null
    /** Расписания, уже обновлённые с момента запуска приложения. */
    val refreshedThisLaunch = HashSet<String>()

    private var afterPermissions: (() -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        afterPermissions?.invoke()
        afterPermissions = null
    }

    // Фото для планера: системный выбор фото (без разрешений) и камера.
    private var photoCallback: ((List<android.net.Uri>) -> Unit)? = null
    private var cameraUri: android.net.Uri? = null
    private val photoPicker = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(6),
    ) { uris -> photoCallback?.invoke(uris); photoCallback = null }
    private val camera = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture(),
    ) { ok -> val u = cameraUri; if (ok && u != null) photoCallback?.invoke(listOf(u)); photoCallback = null }

    fun pickPhotos(onPicked: (List<android.net.Uri>) -> Unit) {
        photoCallback = onPicked
        photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(
            androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly,
        ))
    }

    fun takePhoto(onTaken: (List<android.net.Uri>) -> Unit) {
        val f = java.io.File(java.io.File(cacheDir, "photos").apply { mkdirs() }, "shot_${System.currentTimeMillis()}.jpg")
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", f)
        cameraUri = uri
        photoCallback = onTaken
        runCatching { camera.launch(uri) }.onFailure {
            photoCallback = null
            android.widget.Toast.makeText(this, "Не удалось открыть камеру", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val tabs = listOf(R.id.nav_schedule, R.id.nav_planner, R.id.nav_pass, R.id.nav_grades, R.id.nav_settings)

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
            if (item.itemId != b.bottomNav.selectedItemId) b.bottomNav.haptic()
            showPage(tabs.indexOf(item.itemId))
            true
        }
        val target = intent.getIntExtra(Notifier.EXTRA_TAB, -1).takeIf { it >= 0 }
        val start = target?.takeIf { it != Syncer.TAB_HISTORY }
            ?: if (Prefs(this).hasCredentials) Syncer.TAB_SCHEDULE else Syncer.TAB_SETTINGS
        if (savedInstanceState == null) {
            showTab(start)
            if (target == Syncer.TAB_HISTORY) b.root.post { openHistory() }
            handleShortcut(intent)
            if (!Prefs(this).onboardingDone) b.root.post { Onboarding.show(this) }
            else {
                b.root.post { WhatsNew.showIfUpdated(this) }
                Updater.autoCheck(this, lifecycleScope)
                runCatching { NowWidget.update(this) }
                runCatching { SyncWorker.ensure(applicationContext) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Вернулись в приложение спустя долгое время — ещё раз проверим обновления.
        Updater.autoCheck(this, lifecycleScope, Updater.RESUME_CHECK_MS)
    }

    fun openPass() = showTab(Syncer.TAB_PASS)

    /** Тур по приложению: открываем расписание и по очереди подсвечиваем его элементы и вкладки. */
    fun startTour() {
        showTab(Syncer.TAB_SCHEDULE)
        val decor = window.decorView as android.widget.FrameLayout
        fun tagged(tag: String): () -> View? = { decor.findViewWithTag(tag) }
        fun tab(id: Int): () -> View? = { b.bottomNav.findViewById(id) }
        val steps = listOf(
            TourStep(tagged("tour_selector"), "Выбор расписания",
                "Своё расписание и сохранённые. Последний пункт «Найти и добавить» ищет группы, преподавателей и аудитории."),
            TourStep(tagged("tour_history"), "Изменения",
                "Что изменилось в расписаниях и оценках. Число на колокольчике показывает новые изменения с прошлого просмотра."),
            TourStep(tagged("tour_more"), "Меню расписания",
                "Обновить, вид «неделя», расписание звонков, уведомления о переносах и перенос пар в календарь."),
            TourStep(tagged("tour_card"), "Карточка пары",
                "Нажмите на пару, чтобы отметить контрольную или коллоквиум и поставить напоминание. " +
                    "Смахните пару в сторону, чтобы скрыть её."),
            TourStep(tab(R.id.nav_planner), "Планер", "Все записи по дням. Отметьте выполненное или поправьте напоминание."),
            TourStep(tab(R.id.nav_grades), "Оценки",
                "Рейтинг по дисциплинам. Откройте дисциплину и нажмите «Что если», чтобы задать будущие баллы за КРМ и посмотреть прогноз оценки."),
            TourStep(tab(R.id.nav_pass), "Пропуск",
                "QR-код пропуска с максимальной яркостью."),
            TourStep(tab(R.id.nav_settings), "Настройки", "Вход, календарь, уведомления и звуки, внешний вид, обновления."),
        )
        // Даём ленте открыться и прокрутиться к сегодняшнему дню.
        b.root.postDelayed({
            decor.addView(TourOverlay(this, steps) { Prefs(this).tourDone = true }, android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
            ))
        }, 600)
    }

    fun showOnboarding() = Onboarding.show(this)

    /** «История изменений» — вложенный экран поверх текущей вкладки. */
    fun openHistory() = openNested(HistoryPage(), "history")

    fun openAppearance() = openNested(AppearancePage(), "appearance")

    /** Подэкран настроек: календарь, уведомления, «О приложении». */
    fun openSettings(section: String) = openNested(SettingsPage.of(section), "settings_$section")

    /** Экран дисциплины с «Что если». */
    fun openSubject(term: Int, key: String) = openNested(SubjectPage.of(term, key), "subject")

    /** «Что если» — отдельный экран поверх дисциплины; при выходе введённые баллы теряются. */
    fun openWhatIf(term: Int, key: String) = openNested(SubjectPage.of(term, key, whatIf = true), "whatif")

    private fun openNested(page: Fragment, name: String) {
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.push_in, R.anim.push_out, R.anim.pop_in, R.anim.pop_out)
            .replace(R.id.container, page)
            .addToBackStack(name)
            .commit()
    }

    /** Ярлыки на иконке приложения: «Пропуск» и «Новая запись». */
    private fun handleShortcut(intent: Intent?) {
        when (intent?.action) {
            "ru.susu.calendarsync.OPEN_PASS" -> showTab(Syncer.TAB_PASS)
            "ru.susu.calendarsync.NEW_ENTRY" -> {
                showTab(Syncer.TAB_PLANNER)
                b.root.post {
                    PlannerSheet.showCustom(this, null) {
                        (supportFragmentManager.findFragmentById(R.id.container) as? Page)?.render()
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
        when (val t = intent.getIntExtra(Notifier.EXTRA_TAB, -1)) {
            -1 -> Unit
            Syncer.TAB_HISTORY -> { showTab(Syncer.TAB_SCHEDULE); b.root.post { openHistory() } }
            else -> showTab(t)
        }
    }

    fun showTab(index: Int) {
        b.bottomNav.selectedItemId = tabs[index]
    }

    private fun showPage(index: Int) {
        val page: Fragment = when (index) {
            Syncer.TAB_PASS -> PassPage()
            Syncer.TAB_PLANNER -> PlannerPage()
            Syncer.TAB_GRADES -> GradesPage()
            Syncer.TAB_SETTINGS -> SettingsPage()
            else -> SchedulePage()
        }
        // При смене вкладки закрываем вложенные экраны (например, «Внешний вид»).
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.tab_in, R.anim.tab_out)
            .replace(R.id.container, page).commit()
    }

    fun openTemp(s: SavedSchedule, lessons: List<Lesson>) {
        tempSchedule = s
        tempLessons = lessons
        selectedUid = s.uid
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
