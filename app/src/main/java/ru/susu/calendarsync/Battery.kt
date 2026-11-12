package ru.susu.calendarsync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Работа в фоне. Оптимизация батареи есть на любом Android и проверяется стандартно; поверх неё многие
 * производители добавляют свои ограничения, которые узнать из приложения нельзя, поэтому для них — подсказка.
 */
object Battery {
    /** Система может откладывать фоновые проверки (приложение не в списке исключений оптимизации). */
    fun restricted(c: Context): Boolean =
        c.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(c.packageName) == false

    /** Системный запрос «Разрешить работу в фоне», а если его нет — список оптимизации батареи. */
    fun open(c: Context) {
        runCatching {
            c.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                .setData(Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    /** Что ещё проверить на телефонах, где производитель ограничивает фон сильнее обычного Android. */
    fun vendorHint(): String? {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return when {
            listOf("xiaomi", "redmi", "poco").any { it in m } ->
                "На Xiaomi ещё включите «Автозапуск» и уберите ограничения в «Контроле активности» приложения."
            listOf("huawei", "honor").any { it in m } ->
                "На Huawei и Honor ещё разрешите работу в фоне в разделе «Запуск приложений»."
            listOf("oppo", "realme", "oneplus").any { it in m } ->
                "На этом телефоне ещё разрешите «Автозапуск» и работу в фоне в настройках приложения."
            "vivo" in m -> "На Vivo ещё разрешите «Автозапуск» и высокое потребление в фоне."
            "samsung" in m -> "На Samsung ещё проверьте, что приложения нет в списке «Спящие приложения»."
            else -> null
        }
    }
}
