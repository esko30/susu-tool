package ru.susu.calendarsync

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** Промежуток дат, который попадает в календарь телефона. */
enum class CalendarRange(val label: String) {
    WEEK("Текущая и следующая неделя"),
    MONTH("Месяц вперёд"),
    SEMESTER("Весь семестр"),
    CUSTOM("Свой промежуток…"),
}

/** Настройки и учётные данные (в зашифрованном хранилище Android Keystore). */
class Prefs(context: Context) {
    private val sp: SharedPreferences = shared(context.applicationContext)

    private companion object {
        @Volatile private var cached: SharedPreferences? = null

        /** Открытие зашифрованного хранилища дорогое (Keystore), поэтому делаем его один раз на процесс. */
        fun shared(app: Context): SharedPreferences = cached ?: synchronized(this) {
            cached ?: EncryptedSharedPreferences.create(
                app,
                "susu_prefs",
                MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            ).also { cached = it }
        }
    }

    var login: String
        get() = sp.getString("login", "").orEmpty()
        set(v) = sp.edit { putString("login", v) }

    var password: String
        get() = sp.getString("password", "").orEmpty()
        set(v) = sp.edit { putString("password", v) }

    var displayName: String
        get() = sp.getString("displayName", "").orEmpty()
        set(v) = sp.edit { putString("displayName", v) }

    var reminderMinutes: Int
        get() = sp.getInt("reminder", 15)
        set(v) = sp.edit { putInt("reminder", v) }

    /** Интервал автосинхронизации в часах; 0 — выключено. */
    var syncIntervalHours: Int
        get() = if (sp.contains("syncInterval")) sp.getInt("syncInterval", 6)
        else if (sp.getBoolean("autoSync", true)) 6 else 0
        set(v) = sp.edit { putInt("syncInterval", v) }

    var calendarRange: CalendarRange
        get() = runCatching { CalendarRange.valueOf(sp.getString("range", "")!!) }.getOrDefault(CalendarRange.SEMESTER)
        set(v) = sp.edit { putString("range", v.name) }

    /** Свой промежуток: эпохальные дни (LocalDate.toEpochDay). */
    var customFrom: Long
        get() = sp.getLong("customFrom", 0)
        set(v) = sp.edit { putLong("customFrom", v) }

    var customTo: Long
        get() = sp.getLong("customTo", 0)
        set(v) = sp.edit { putLong("customTo", v) }

    var monitorGrades: Boolean
        get() = sp.getBoolean("monitorGrades", true)
        set(v) = sp.edit { putBoolean("monitorGrades", v) }

    /** Текущий семестр по учебному плану; 0 — ещё не загружали. */
    var currentTerm: Int
        get() = sp.getInt("currentTerm", 0)
        set(v) = sp.edit { putInt("currentTerm", v) }

    /** Код электронного пропуска и время его загрузки (портал обновляет раз в 12 часов). */
    var passTicket: String
        get() = sp.getString("passTicket", "").orEmpty()
        set(v) = sp.edit { putString("passTicket", v) }

    var passTicketUpdated: Long
        get() = sp.getLong("passTicketUpdated", 0)
        set(v) = sp.edit { putLong("passTicketUpdated", v) }

    /** Звук уведомлений: ключ из Notifier.SOUNDS или «uri:…» для звука из телефона. */
    var soundSchedule: String
        get() = sp.getString("soundSchedule", "chime").orEmpty()
        set(v) = sp.edit { putString("soundSchedule", v) }

    var soundGrades: String
        get() = sp.getString("soundGrades", "arpeggio").orEmpty()
        set(v) = sp.edit { putString("soundGrades", v) }

    /** Время последнего просмотра истории — по нему считается счётчик на колокольчике. */
    var historySeen: Long
        get() = sp.getLong("historySeen", 0)
        set(v) = sp.edit { putLong("historySeen", v) }

    var onboardingDone: Boolean
        get() = sp.getBoolean("onboardingDone", false)
        set(v) = sp.edit { putBoolean("onboardingDone", v) }

    var tourDone: Boolean
        get() = sp.getBoolean("tourDone", false)
        set(v) = sp.edit { putBoolean("tourDone", v) }

    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdateCheck", true)
        set(v) = sp.edit { putBoolean("autoUpdateCheck", v) }

    /** Когда фоновая проверка изменений последний раз отработала (успешно или с ошибкой). */
    var lastBackgroundCheck: Long
        get() = sp.getLong("lastBackgroundCheck", 0)
        set(v) = sp.edit { putLong("lastBackgroundCheck", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    /** Версия, «что нового» которой уже показали. */
    var whatsNewSeen: String
        get() = sp.getString("whatsNewSeen", "").orEmpty()
        set(v) = sp.edit { putString("whatsNewSeen", v) }

    var skippedVersion: String
        get() = sp.getString("skippedVersion", "").orEmpty()
        set(v) = sp.edit { putString("skippedVersion", v) }

    /** Донат: первый запуск, число запусков, когда снова предложить, поддержал ли, когда ушёл в банк. */
    var firstLaunchAt: Long
        get() = sp.getLong("firstLaunchAt", 0)
        set(v) = sp.edit { putLong("firstLaunchAt", v) }

    var launchCount: Int
        get() = sp.getInt("launchCount", 0)
        set(v) = sp.edit { putInt("launchCount", v) }

    var donateNextAt: Long
        get() = sp.getLong("donateNextAt", 0)
        set(v) = sp.edit { putLong("donateNextAt", v) }

    var donated: Boolean
        get() = sp.getBoolean("donated", false)
        set(v) = sp.edit { putBoolean("donated", v) }

    var donatePendingSince: Long
        get() = sp.getLong("donatePendingSince", 0)
        set(v) = sp.edit { putLong("donatePendingSince", v) }

    /** Профиль: данные с портала получены (один раз после входа). */
    var profileFetched: Boolean
        get() = sp.getBoolean("profileFetched", false)
        set(v) = sp.edit { putBoolean("profileFetched", v) }

    var profileGroup: String
        get() = sp.getString("profileGroup", "").orEmpty()
        set(v) = sp.edit { putString("profileGroup", v) }

    /** Аватарка: portal — с портала, custom — своё фото, none — инициалы. */
    var avatarMode: String
        get() = sp.getString("avatarMode", Profile.AVATAR_PORTAL).orEmpty()
        set(v) = sp.edit { putString("avatarMode", v) }

    /** Шрифт: false — «Эффективный» (Noto Sans), true — «Обычный», как в телефоне. */
    var systemFont: Boolean
        get() = sp.getBoolean("systemFont", false)
        set(v) = sp.edit { putBoolean("systemFont", v) }

    /** Тема приложения с выбранным шрифтом — для активности и полноэкранных окон. */
    val themeRes: Int get() = when {
        systemFont && tightLines -> R.style.Theme_SusuCalendar_SystemFont_Tight
        systemFont -> R.style.Theme_SusuCalendar_SystemFont
        tightLines -> R.style.Theme_SusuCalendar_Tight
        else -> R.style.Theme_SusuCalendar
    }

    /** Межстрочный интервал: false — обычный, true — компактный. */
    var tightLines: Boolean
        get() = sp.getBoolean("tightLines", false)
        set(v) = sp.edit { putBoolean("tightLines", v) }

    /** Номер цитаты, показанной в приветствии в прошлый раз. */
    var quoteIndex: Int
        get() = sp.getInt("quoteIndex", -1)
        set(v) = sp.edit { putInt("quoteIndex", v) }

    /** Скрытые пользователем пары (свайпом в ленте). */
    var hiddenLessons: Set<String>
        get() = sp.getStringSet("hiddenLessons", emptySet()).orEmpty().toSet()
        set(v) = sp.edit { putStringSet("hiddenLessons", v) }

    /** Вид расписания: false — лента, true — неделя сеткой. */
    var weekView: Boolean
        get() = sp.getBoolean("weekView", false)
        set(v) = sp.edit { putBoolean("weekView", v) }

    /** Когда последний раз успешно обновились данные и сколько пар в своём расписании. */
    var lastSyncAt: Long
        get() = sp.getLong("lastSyncAt", 0)
        set(v) = sp.edit { putLong("lastSyncAt", v) }

    var lastSyncLessons: Int
        get() = sp.getInt("lastSyncLessons", 0)
        set(v) = sp.edit { putInt("lastSyncLessons", v) }

    var lastStatus: String
        get() = sp.getString("lastStatus", "").orEmpty()
        set(v) = sp.edit { putString("lastStatus", v) }

    var display: DisplayOptions
        get() = DisplayOptions(
            pairNumber = sp.getBoolean("d_pair2", false),
            shortTypes = sp.getBoolean("d_short", true),
            bigRoom = sp.getBoolean("d_room", true),
            teacher = sp.getBoolean("d_teacher", true),
            groups = sp.getBoolean("d_groups", true),
            progress = sp.getBoolean("d_progress", true),
            countdown = sp.getBoolean("d_countdown", true),
            hidePast = sp.getBoolean("d_hidePast", false),
            glow = sp.getBoolean("d_glow", true),
            typeTag = sp.getBoolean("d_tag", true),
            gradient = sp.getBoolean("d_gradient", true),
            compact = sp.getBoolean("d_compact", false),
            windows = sp.getBoolean("d_windows", true),
            collapsePast = sp.getBoolean("d_collapsePast", true),
            mini = sp.getBoolean("d_mini", false),
            theme = sp.getInt("d_theme", 0),
        )
        set(v) = sp.edit {
            putBoolean("d_pair2", v.pairNumber)
            putBoolean("d_short", v.shortTypes)
            putBoolean("d_room", v.bigRoom)
            putBoolean("d_teacher", v.teacher)
            putBoolean("d_groups", v.groups)
            putBoolean("d_progress", v.progress)
            putBoolean("d_countdown", v.countdown)
            putBoolean("d_hidePast", v.hidePast)
            putBoolean("d_glow", v.glow)
            putBoolean("d_tag", v.typeTag)
            putBoolean("d_gradient", v.gradient)
            putBoolean("d_compact", v.compact)
            putBoolean("d_windows", v.windows)
            putBoolean("d_collapsePast", v.collapsePast)
            putBoolean("d_mini", v.mini)
            putInt("d_theme", v.theme)
        }

    val hasCredentials get() = login.isNotEmpty() && password.isNotEmpty()

    fun clear() = sp.edit { clear() }
}
