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
    /** Приветствие и анимация входа — один раз за запуск. */
    var greetPending = false

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
            android.widget.Toast.makeText(this, "Не получилось открыть камеру", android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    private val tabs = listOf(R.id.nav_schedule, R.id.nav_planner, R.id.nav_pass, R.id.nav_grades, R.id.nav_settings)

    override fun onCreate(savedInstanceState: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(AppearancePage.nightMode(Prefs(this).display.theme))
        setTheme(Prefs(this).themeRes)
        super.onCreate(savedInstanceState)
        // Выход из заставки: иконка взлетает к шапке и тает, расписание проявляется.
        if (android.os.Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { v ->
            val icon: View? = v.iconView
            icon?.animate()?.translationY(-v.height * 0.32f)?.scaleX(0.45f)?.scaleY(0.45f)?.alpha(0f)
                ?.setDuration(380)?.setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())?.start()
            v.animate().alpha(0f).setStartDelay(140).setDuration(320).withEndAction { v.remove() }.start()
        }
        if (savedInstanceState == null) greetPending = true
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Notifier.ensureChannels(this)
        // Пока открыта клавиатура, нижняя панель вкладок не нужна и только отнимает место.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(b.root) { v, insets ->
            b.bottomNav.visibility = if (insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime())) View.GONE else View.VISIBLE
            androidx.core.view.ViewCompat.onApplyWindowInsets(v, insets)
        }

        b.bottomNav.setOnItemReselectedListener { }
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
            openSubjectFrom(intent)
            handleShortcut(intent)
            if (!Prefs(this).onboardingDone) b.root.post { Onboarding.show(this) }
            else {
                Donate.countLaunch(this)
                b.root.post {
                    // В день «Что нового» о донате не просим; иначе — на расписании, через пару секунд.
                    if (!WhatsNew.showIfUpdated(this)) b.root.postDelayed({
                        if (b.bottomNav.selectedItemId == tabs[Syncer.TAB_SCHEDULE]) Donate.maybeOffer(this)
                    }, 2500)
                }
                Updater.autoCheck(this, lifecycleScope)
                runCatching { NowWidget.update(this) }
                runCatching { SyncWorker.ensure(applicationContext) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Вернулись из банка после «Поддержать» — возможно, пора сказать спасибо.
        Donate.onResume(this)
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
                "Здесь ваше расписание и сохранённые. Через «Найти и добавить» ищутся группы, преподаватели и аудитории."),
            TourStep(tagged("tour_history"), "Изменения",
                "Что поменялось в расписании и оценках. Число на колокольчике значит, сколько нового."),
            TourStep(tagged("tour_more"), "Меню расписания",
                "Настройки расписания, вид по неделям, звонки и своя пара."),
            TourStep(tagged("tour_card"), "Карточка пары",
                "Нажмите на пару, чтобы отметить контрольную и поставить напоминание. " +
                    "Смахните пару, чтобы скрыть её."),
            TourStep(tab(R.id.nav_planner), "Планер", "Все записи по дням. Отмечайте сделанное."),
            TourStep(tab(R.id.nav_grades), "Оценки",
                "Баллы по предметам. Откройте предмет и нажмите «Что если», чтобы прикинуть оценку."),
            TourStep(tab(R.id.nav_pass), "Пропуск",
                "QR-код пропуска, работает и без интернета."),
            TourStep(tab(R.id.nav_settings), "Настройки", "Вход, уведомления, календарь и внешний вид."),
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

    fun openDiploma() = openNested(DiplomaPage(), "diploma")

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
        openSubjectFrom(intent)
    }

    /** Уведомление об оценках по одному предмету: сразу открываем предмет. */
    private fun openSubjectFrom(intent: Intent) {
        val key = intent.getStringExtra(Notifier.EXTRA_SUBJECT) ?: return
        val term = intent.getIntExtra(Notifier.EXTRA_TERM, 0)
        intent.removeExtra(Notifier.EXTRA_SUBJECT)
        if (key.startsWith("sample-")) return // пример из настроек: такого предмета нет
        b.root.post { openSubject(term, key) }
    }

    fun showTab(index: Int) {
        // Вкладка уже выбрана (при запуске это «Расписание» по умолчанию), а страницы ещё нет:
        // панель сочтёт это повторным нажатием и не откроет страницу, поэтому открываем сами.
        if (b.bottomNav.selectedItemId == tabs[index] && supportFragmentManager.findFragmentById(R.id.container) == null) {
            showPage(index)
            return
        }
        b.bottomNav.selectedItemId = tabs[index]
    }

    private var currentTab = -1

    private fun showPage(index: Int) {
        // Направление сдвига: вкладка правее — новая страница въезжает справа, левее — слева.
        val forward = index >= currentTab
        currentTab = index
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
            .setCustomAnimations(
                if (forward) R.anim.tab_in_right else R.anim.tab_in_left,
                if (forward) R.anim.tab_out_left else R.anim.tab_out_right,
            )
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
    private var createdAt = 0L

    abstract fun build(): View

    override fun onCreateView(inflater: android.view.LayoutInflater, container: ViewGroup?, s: Bundle?): View =
        FrameLayout(requireContext()).also { root = it; createdAt = android.os.SystemClock.uptimeMillis(); it.addView(build()) }

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
        // Сразу после открытия вкладки (она и так въезжает) второе затухание выглядит как двойное нажатие.
        val justOpened = android.os.SystemClock.uptimeMillis() - createdAt < 700
        if (fade && old != null && !justOpened) {
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
        is AuthException -> e.message ?: "Не получилось войти"
        is java.net.UnknownHostException -> "Нет интернета"
        else -> e.message?.let { "Не получилось. $it" } ?: "Что-то пошло не так"
    }
}
