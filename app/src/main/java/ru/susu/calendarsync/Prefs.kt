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
    private val sp: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "susu_prefs",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

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

    var autoUpdateCheck: Boolean
        get() = sp.getBoolean("autoUpdateCheck", true)
        set(v) = sp.edit { putBoolean("autoUpdateCheck", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    var skippedVersion: String
        get() = sp.getString("skippedVersion", "").orEmpty()
        set(v) = sp.edit { putString("skippedVersion", v) }

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
            passSwipe = sp.getBoolean("d_passSwipe", true),
            passHint = sp.getBoolean("d_passHint", true),
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
            putBoolean("d_passSwipe", v.passSwipe)
            putBoolean("d_passHint", v.passHint)
            putInt("d_theme", v.theme)
        }

    val hasCredentials get() = login.isNotEmpty() && password.isNotEmpty()

    fun clear() = sp.edit { clear() }
}
