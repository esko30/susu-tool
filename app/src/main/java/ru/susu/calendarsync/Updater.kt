package ru.susu.calendarsync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Новая версия из GitHub Releases. */
data class UpdateInfo(val version: String, val notes: String, val apkUrl: String, val size: Long)

/**
 * Обновления через GitHub Releases: приложение смотрит последний релиз репозитория
 * BuildConfig.UPDATE_REPO, сравнивает версию из тега (v2.1) с текущей и предлагает
 * скачать APK. Установку подтверждает пользователь в системном окне Android.
 */
object Updater {
    private const val DAY_MS = 24 * 3600_000L

    val enabled get() = BuildConfig.UPDATE_REPO.isNotBlank()

    /** «2.10» новее «2.9»: сравнение по числам через точку. */
    fun isNewer(remote: String, local: String): Boolean {
        val a = remote.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        val b = local.trim().removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Последний релиз, если он новее установленной версии; иначе null. */
    fun check(): UpdateInfo? {
        if (!enabled) return null
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "SUSU-Tool-Android")
            if (conn.responseCode == 404) return null // релизов ещё нет
            if (conn.responseCode !in 200..299) throw java.io.IOException("GitHub: HTTP ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val version = o.optString("tag_name").removePrefix("v")
            if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk")) {
                    return UpdateInfo(version, o.optString("body").trim(), a.getString("browser_download_url"), a.optLong("size"))
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Проверка при запуске — не чаще раза в сутки и только если не отключена в настройках. */
    fun autoCheck(activity: Activity, scope: LifecycleCoroutineScope) {
        val prefs = Prefs(activity)
        if (!enabled || !prefs.autoUpdateCheck) return
        if (System.currentTimeMillis() - prefs.lastUpdateCheck < DAY_MS) return
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { check() }.getOrNull() }
            prefs.lastUpdateCheck = System.currentTimeMillis()
            if (info != null && info.version != prefs.skippedVersion && !activity.isFinishing) offer(activity, scope, info)
        }
    }

    /** Ручная проверка из настроек; [done] получает текст результата. */
    fun checkNow(activity: Activity, scope: LifecycleCoroutineScope, done: (String) -> Unit) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { check() } }
            Prefs(activity).lastUpdateCheck = System.currentTimeMillis()
            r.onSuccess { info ->
                if (info == null) done("У вас последняя версия ${BuildConfig.VERSION_NAME}")
                else { done("Доступна версия ${info.version}"); offer(activity, scope, info) }
            }.onFailure { done("Не удалось проверить обновления: ${it.message}") }
        }
    }

    fun offer(activity: Activity, scope: LifecycleCoroutineScope, info: UpdateInfo) {
        val size = if (info.size > 0) " · ${"%.1f".format(info.size / 1048576.0)} МБ" else ""
        MaterialAlertDialogBuilder(activity)
            .setTitle("Доступна версия ${info.version}$size")
            .setMessage(info.notes.ifBlank { "Новая версия SUSU Tool." })
            .setPositiveButton("Обновить") { _, _ -> download(activity, scope, info) }
            .setNegativeButton("Позже", null)
            .setNeutralButton("Пропустить версию") { _, _ -> Prefs(activity).skippedVersion = info.version }
            .show()
    }

    private fun download(activity: Activity, scope: LifecycleCoroutineScope, info: UpdateInfo) {
        // Android 8+: установка из приложения требует разрешения «Установка неизвестных приложений».
        if (!activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("Нужно разрешение")
                .setMessage("Разрешите SUSU Tool устанавливать обновления, затем снова нажмите «Обновить».")
                .setPositiveButton("Открыть настройки") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        val bar = LinearProgressIndicator(activity).apply { max = 100; setPadding(activity.dp(24), activity.dp(16), activity.dp(24), 0) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Загрузка версии ${info.version}…")
            .setView(bar)
            .setCancelable(false)
            .show()
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching { fetch(activity, info.apkUrl) { p -> bar.post { bar.setProgressCompat(p, true) } } }
            }
            dialog.dismiss()
            file.onSuccess { install(activity, it) }.onFailure {
                MaterialAlertDialogBuilder(activity).setMessage("Не удалось скачать обновление: ${it.message}")
                    .setPositiveButton("OK", null).show()
            }
        }
    }

    private fun fetch(context: Context, url: String, progress: (Int) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val out = File(dir, "susu-tool.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "SUSU-Tool-Android")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        read += n
                        if (total > 0) progress((read * 100 / total).toInt())
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return out
    }

    private fun install(activity: Activity, apk: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", apk)
        activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
