package ru.susu.calendarsync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import java.io.File

/**
 * Профиль в настройках: аватарка, ФИО и группа. Всё берётся с портала один раз после входа;
 * аватарку можно заменить своим фото или убрать (тогда показываются инициалы).
 */
object Profile {
    const val AVATAR_PORTAL = "portal"
    const val AVATAR_CUSTOM = "custom"
    const val AVATAR_NONE = "none"

    private fun portalFile(c: Context) = File(c.filesDir, "avatar_portal.jpg")
    private fun customFile(c: Context) = File(c.filesDir, "avatar_custom.jpg")

    /** Один раз после входа: аватарка и ФИО из данных пользователя на портале. Ошибки не мешают входу. */
    fun fetchOnce(context: Context, token: String) {
        val prefs = Prefs(context)
        if (prefs.profileFetched) return
        runCatching {
            val json = JSONObject(SusuApi.get(token, "/api/User/GetUserInfo"))
            val name = listOf("lastName", "firstName", "middleName")
                .map { json.optString(it) }.filter { it.isNotBlank() && it != "null" }.joinToString(" ")
            if (name.isNotBlank()) prefs.displayName = name
            groupFrom(json)?.let { prefs.profileGroup = it }
            for (key in listOf("avatar", "photo")) {
                val raw = json.optString(key).takeIf { it.isNotBlank() && it != "null" } ?: continue
                val bytes = Base64.decode(raw.substringAfter("base64,"), Base64.DEFAULT)
                if (BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null) {
                    portalFile(context).writeBytes(bytes)
                    break
                }
            }
        }
        prefs.profileFetched = true
    }

    /** Название группы из данных студента, если портал его прислал. */
    private fun groupFrom(json: JSONObject): String? {
        val st = json.optJSONArray("student")?.optJSONObject(0) ?: return null
        val keys = st.keys().asSequence().toList()
        val key = keys.firstOrNull { it.equals("groupName", true) }
            ?: keys.firstOrNull { it.contains("group", true) && it.contains("name", true) }
            ?: keys.firstOrNull { it.equals("group", true) }
        return key?.let { st.optString(it) }?.takeIf { it.isNotBlank() && it != "null" && it.length <= 30 }
    }

    /** Группа: с портала, а если её там нет, то самая частая группа в своём расписании. */
    fun group(context: Context): String {
        Prefs(context).profileGroup.takeIf { it.isNotBlank() }?.let { return it }
        val store = Store(context)
        val own = store.own() ?: return ""
        if (own.kind != ScheduleKind.GROUP) return ""
        return store.lessons(own.uid).asSequence()
            .flatMap { l -> l.details.lineSequence().filter { it.startsWith("Группы:") }.flatMap { it.substringAfter(":").split(",").asSequence() } }
            .map { it.trim() }.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key.orEmpty()
    }

    fun avatar(context: Context): Bitmap? {
        val f = when (Prefs(context).avatarMode) {
            AVATAR_CUSTOM -> customFile(context)
            AVATAR_NONE -> return null
            else -> portalFile(context)
        }
        return if (f.exists()) BitmapFactory.decodeFile(f.path) else null
    }

    fun hasPortalAvatar(context: Context) = portalFile(context).exists()

    /** Своё фото вместо аватарки с портала (уменьшенная копия в папке приложения). */
    fun setCustom(context: Context, uri: Uri): Boolean {
        val name = PlannerPhotos.import(context, uri) ?: return false
        val src = PlannerPhotos.file(context, name)
        src.copyTo(customFile(context), overwrite = true)
        src.delete()
        Prefs(context).avatarMode = AVATAR_CUSTOM
        return true
    }

    fun clear(context: Context) {
        portalFile(context).delete()
        customFile(context).delete()
    }

    /** Круглая аватарка; без фото — инициалы на цветном фоне. */
    fun view(context: Context, sizeDp: Int, name: String, onClick: () -> Unit): View {
        val size = context.dp(sizeDp)
        val bmp = avatar(context)
        val card = MaterialCardView(context).apply {
            radius = size / 2f
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(context.color(com.google.android.material.R.attr.colorPrimaryContainer))
            layoutParams = android.widget.LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
            contentDescription = "Аватарка. Нажмите, чтобы изменить"
        }
        if (bmp != null) {
            card.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }, FrameLayout.LayoutParams(size, size))
        } else {
            val initials = name.split(" ").filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
            card.addView(TextView(context).apply {
                text = initials.ifBlank { "?" }
                gravity = Gravity.CENTER
                textSize = sizeDp / 2.8f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(context.color(com.google.android.material.R.attr.colorOnPrimaryContainer))
            }, FrameLayout.LayoutParams(size, size))
        }
        return card
    }
}
