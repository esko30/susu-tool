package ru.susu.calendarsync

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Запись файла целиком: сначала во временный рядом, потом замена одним переименованием.
 * Экран, читающий файл одновременно с фоновой проверкой, никогда не увидит половину JSON.
 */
fun File.writeAtomic(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) {
        // Не вышло заменить (редко, на старых файловых системах) — пишем напрямую.
        writeText(text)
        tmp.delete()
    }
}

/** Даты и время для подписей «Обновлено 12 ноября, 14:24». */
object Fmt {
    private val ru = Locale("ru")

    fun dayTime(ms: Long): String = SimpleDateFormat("d MMMM, HH:mm", ru).format(Date(ms))
    fun dayYearTime(ms: Long): String = SimpleDateFormat("d MMMM yyyy, HH:mm", ru).format(Date(ms))
    fun time(ms: Long): String = SimpleDateFormat("HH:mm", ru).format(Date(ms))
}
