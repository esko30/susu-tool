package ru.susu.calendarsync

import android.content.Context

/** Короткие ободряющие цитаты для приветствия (res/raw/quotes.txt, по одной в строке). */
object Quotes {
    @Volatile private var cache: List<String>? = null

    fun all(context: Context): List<String> = cache ?: runCatching {
        context.resources.openRawResource(R.raw.quotes).bufferedReader(Charsets.UTF_8).readLines()
            .map { it.trim() }.filter { it.isNotEmpty() }
    }.getOrDefault(emptyList()).also { cache = it }
}
