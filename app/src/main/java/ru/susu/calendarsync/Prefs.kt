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

    /** Токен мобильного приложения Moodle (edu.susu.ru) для отметки посещаемости. */
    var moodleToken: String
        get() = sp.getString("moodleToken", "").orEmpty()
        set(v) = sp.edit { putString("moodleToken", v) }

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
    /** Тема приложения: шрифт Noto Sans. */
    val themeRes: Int get() = R.style.Theme_SusuCalendar

    /** За сколько минут до первой пары будить (будильники на неделю). */
    var wakeLead: Int
        get() = sp.getInt("wakeLead", 90)
        set(v) = sp.edit { putInt("wakeLead", v) }

    /** Новогоднее оформление (снег, гирлянда, ледяная корочка). Пока не трогали — включено с 1 декабря по 14 января. */
    var snow: Boolean
        get() = if (sp.contains("snow")) sp.getBoolean("snow", false) else SnowView.season(Clock.today())
        set(v) = sp.edit { putBoolean("snow", v) }

    /** Новогоднее оформление уже включали принудительно после обновления (один раз за зиму). */
    var holidayForced: Int
        get() = sp.getInt("holidayForced", 0)
        set(v) = sp.edit { putInt("holidayForced", v) }

    /** Не показывать консультации в ленте, сетке недели и виджетах. */
    var hideConsultations: Boolean
        get() = sp.getBoolean("hideConsultations", false)
        set(v) = sp.edit { putBoolean("hideConsultations", v) }

    /** Сколько часов дежурит «Дежурный» (1–12). */
    var dutyHours: Int
        get() = sp.getInt("dutyHours", 4)
        set(v) = sp.edit { putInt("dutyHours", v) }

    /** «Дежурный» включён до этого момента (мс); 0 — выключен. */
    var ultraUntil: Long
        get() = sp.getLong("ultraUntil", 0)
        set(v) = sp.edit { putLong("ultraUntil", v) }

    /** Черновик незаконченной записи планера (JSON). */
    var plannerDraft: String
        get() = sp.getString("plannerDraft", "").orEmpty()
        set(v) = sp.edit { putString("plannerDraft", v) }

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
            shortTypes = sp.getBoolean("d_short", true),
            teacher = sp.getBoolean("d_teacher", true),
            groups = sp.getBoolean("d_groups", true),
            typeTag = sp.getBoolean("d_tag", true),
            theme = sp.getInt("d_theme", 0),
        )
        set(v) = sp.edit {
            putBoolean("d_short", v.shortTypes)
            putBoolean("d_teacher", v.teacher)
            putBoolean("d_groups", v.groups)
            putBoolean("d_tag", v.typeTag)
            putInt("d_theme", v.theme)
        }

    val hasCredentials get() = login.isNotEmpty() && password.isNotEmpty()

    fun clear() = sp.edit { clear() }
}
