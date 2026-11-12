package ru.susu.calendarsync

import android.content.Context

/**
 * Токен доступа в памяти процесса. Пароль хранится в Prefs (зашифрованно),
 * при истечении токена выполняется повторный вход.
 */
object Session {
    @Volatile private var auth: LoginResult? = null

    @Synchronized
    fun login(context: Context, force: Boolean = false): LoginResult {
        auth?.takeIf { !force }?.let { return it }
        val prefs = Prefs(context)
        check(prefs.hasCredentials) { "Войдите в «Настройках»" }
        val result = SusuApi.login(prefs.login, prefs.password)
        auth = result
        prefs.displayName = result.displayName
        Store(context).ensureOwnSchedule(result)
        Profile.fetchOnce(context, result.accessToken)
        return result
    }

    /** Выполняет запрос с токеном; при истёкшей сессии один раз входит заново. */
    fun <T> call(context: Context, block: (token: String) -> T): T {
        val first = login(context)
        return try {
            block(first.accessToken)
        } catch (e: AuthException) {
            block(login(context, force = true).accessToken)
        }
    }

    fun reset() {
        auth = null
    }
}
