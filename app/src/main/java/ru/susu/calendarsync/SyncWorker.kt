package ru.susu.calendarsync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

object Syncer {
    const val TAB_SCHEDULE = 0
    const val TAB_PLANNER = 1
    const val TAB_PASS = 2
    const val TAB_GRADES = 3
    const val TAB_SETTINGS = 4
    /** Не вкладка: экран «История изменений», открывается колокольчиком и из уведомлений. */
    const val TAB_HISTORY = 10

    /**
     * Загружает одно расписание, сравнивает с кэшем (если включено наблюдение — пишет историю и
     * присылает уведомление) и при необходимости переносит в календарь. Вызывать не из UI-потока.
     */
    fun syncSchedule(context: Context, s: SavedSchedule): List<Lesson> {
        val store = Store(context)
        val prefs = Prefs(context)
        val lessons = Session.call(context) { SusuApi.schedule(it, s.kind, s.id) }

        if (s.monitor && store.hasCache(s.uid)) {
            val today = LocalDate.now(CalendarSync.SUSU_ZONE).toString()
            val changes = diffLessons(store.lessons(s.uid), lessons, today)
            if (changes.isNotEmpty()) {
                val source = if (s.own) "Моё расписание" else s.label
                ChangeLog(context).add(ChangeSet(System.currentTimeMillis(), source, changes))
                Notifier.notifyChanges(context, Notifier.CHANNEL_SCHEDULE, "Изменения: $source", changes, TAB_HISTORY)
            }
        }
        store.saveLessons(s.uid, lessons)
        store.find(s.uid)?.let { store.upsert(it.copy(lastUpdate = System.currentTimeMillis())) }

        if (hasCalendarPermission(context)) {
            val cal = CalendarSync(context)
            val display = prefs.display
            val forCalendar = lessons.map { it.copy(eventType = display.typeName(it.eventType)) }
            if (s.toCalendar) cal.sync(s.calendarKey, s.calendarTitle, forCalendar, prefs.reminderMinutes, DateWindow.of(prefs))
            else cal.deleteCalendar(s.calendarKey)
        }
        return lessons
    }

    /** Загружает оценки текущего семестра и сообщает о новых КРМ и изменениях баллов. */
    fun syncGrades(context: Context, term: Int? = null): GradesSnapshot {
        val gs = GradesStore(context)
        val snapshot = Session.call(context) { token ->
            val (current, count) = Grades.studyPlan(token)
            Prefs(context).currentTerm = current
            Grades.fetch(token, term ?: current, count)
        }
        val old = gs.load(snapshot.term)
        if (old != null) {
            val changes = Grades.diff(old, snapshot)
            if (changes.isNotEmpty()) {
                ChangeLog(context).add(ChangeSet(System.currentTimeMillis(), "Оценки, ${snapshot.term} семестр", changes))
                val krm = changes.count { it.kind == Change.Kind.KRM_NEW }
                val title = if (krm > 0) "Новые КРМ и оценки" else "Изменения в оценках"
                Notifier.notifyChanges(context, Notifier.CHANNEL_GRADES, title, changes, TAB_HISTORY)
            }
        }
        gs.save(snapshot)
        return snapshot
    }

    /** Полный цикл: все сохранённые расписания + оценки. */
    suspend fun run(context: Context): String = withContext(Dispatchers.IO) {
        val prefs = Prefs(context)
        check(prefs.hasCredentials) { "Не указаны логин и пароль" }
        val auth = Session.login(context, force = true)

        val errors = ArrayList<String>()
        var lessonsCount = 0
        for (s in Store(context).saved()) {
            if (!s.own && !s.monitor && !s.toCalendar) continue
            try {
                val lessons = syncSchedule(context, s)
                if (s.own) lessonsCount = lessons.size
            } catch (e: Exception) {
                errors += "${s.label}: ${e.message}"
            }
        }
        if (prefs.monitorGrades && !auth.isInstructor) {
            try {
                syncGrades(context)
            } catch (e: Exception) {
                errors += "Оценки: ${e.message}"
            }
        }

        // Время и число пар показываются в карточке профиля; в статусе остаются только ошибки.
        prefs.lastSyncAt = System.currentTimeMillis()
        prefs.lastSyncLessons = lessonsCount
        errors.joinToString("\n")
    }

    fun hasCalendarPermission(context: Context) = listOf(
        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
    ).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "susu_periodic_sync"

        /**
         * Следит, чтобы фоновая проверка стояла в очереди: при запуске приложения, после перезагрузки
         * и обновления. Таймер не сбрасывается (KEEP), если задача уже есть.
         */
        fun ensure(context: Context) = enqueue(context, ExistingPeriodicWorkPolicy.KEEP)

        /** Перепланирует автосинхронизацию по текущим настройкам (после смены интервала). */
        fun schedule(context: Context) = enqueue(context, ExistingPeriodicWorkPolicy.UPDATE)

        private fun enqueue(context: Context, policy: ExistingPeriodicWorkPolicy) {
            val prefs = Prefs(context)
            val hours = prefs.syncIntervalHours
            val wm = WorkManager.getInstance(context)
            if (hours <= 0 || !prefs.hasCredentials) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<SyncWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, policy, request)
        }
    }

    override suspend fun doWork(): Result {
        val prefs = Prefs(applicationContext)
        prefs.lastBackgroundCheck = System.currentTimeMillis()
        return try {
            prefs.lastStatus = Syncer.run(applicationContext)
            Result.success()
        } catch (e: AuthException) {
            prefs.lastStatus = "Ошибка входа: ${e.message}"
            Result.failure()
        } catch (e: Exception) {
            prefs.lastStatus = "Ошибка автосинхронизации: ${e.message}"
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }
}
