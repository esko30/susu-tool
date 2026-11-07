package ru.susu.calendarsync

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.util.UUID

/**
 * Фото к записям планера: копии лежат в личной папке приложения (уменьшенные до 2048 px),
 * в записи хранятся только имена файлов.
 */
object PlannerPhotos {
    private const val MAX_SIDE = 2048

    fun dir(context: Context) = File(context.filesDir, "planner_photos").apply { mkdirs() }
    fun file(context: Context, name: String) = File(dir(context), name)

    fun delete(context: Context, names: Collection<String>) = names.forEach { file(context, it).delete() }
    fun clear(context: Context) { dir(context).deleteRecursively() }

    /** Копирует картинку в папку приложения; поворот по EXIF учитывается. Возвращает имя файла. */
    fun import(context: Context, uri: Uri): String? = runCatching {
        val bmp = decode(context, uri) ?: return null
        val name = "p_${UUID.randomUUID()}.jpg"
        file(context, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        bmp.recycle()
        name
    }.getOrNull()

    private fun decode(context: Context, uri: Uri): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { d, info, _ ->
                val w = info.size.width; val h = info.size.height
                val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(w, h))
                d.setTargetSize((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
                d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }

    /** Уменьшенная копия для миниатюры. */
    fun thumb(context: Context, name: String, size: Int): Bitmap? = runCatching {
        val f = file(context, name)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Миниатюра со скруглёнными углами. */
    fun thumbView(context: Context, name: String, sizeDp: Int, onClick: () -> Unit): View {
        val size = context.dp(sizeDp)
        return MaterialCardView(context).apply {
            radius = context.dp(12).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(size, size).apply { marginEnd = context.dp(8) }
            addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(thumb(context, name, size))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { onClick() }
        }
    }

    /** Ряд миниатюр для карточки в списке планера. */
    fun strip(context: Context, names: List<String>, onClick: (String) -> Unit): View {
        val row = context.horizontal()
        names.forEach { n -> row.addView(thumbView(context, n, 56) { onClick(n) }) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            setPadding(0, context.dp(8), 0, 0)
        }
    }

    /** Отправить фото в другое приложение (мессенджер, почта, галерея). */
    fun share(context: Context, name: String) {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.files", file(context, name))
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("image/jpeg")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(android.content.Intent.createChooser(send, "Поделиться фото"))
    }

    /** Фото на весь экран: нажатие закрывает, внизу «Поделиться» и (если [onDelete]) «Удалить». */
    fun view(context: Context, name: String, onDelete: (() -> Unit)? = null) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val img = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(BitmapFactory.decodeFile(file(context, name).path))
            setOnClickListener { dialog.dismiss() }
        }
        root.addView(img, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val bar = context.horizontal().apply {
            gravity = Gravity.CENTER
            setPadding(context.dp(16), context.dp(16), context.dp(16), context.dp(40))
            addView(context.button("Поделиться", outlined = true) { share(context, name) })
            if (onDelete != null) {
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(context.dp(12), 1) })
                addView(context.button("Удалить") {
                    com.google.android.material.dialog.MaterialAlertDialogBuilder(context)
                        .setMessage("Удалить фото?")
                        .setPositiveButton("Удалить") { _, _ -> dialog.dismiss(); onDelete() }
                        .setNegativeButton("Отмена", null)
                        .show()
                })
            }
        }
        root.addView(bar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        dialog.setContentView(root)
        dialog.show()
    }

    /**
     * Блок «Фото» в панели записи: миниатюры и кнопки «Галерея» / «Камера».
     * Новые снимки удаляются, если запись не сохранили; убранные удаляются при сохранении.
     */
    class Editor(private val context: Context, initial: List<String>) {
        private val current = initial.toMutableList()
        private val added = HashSet<String>()
        private val removed = HashSet<String>()
        private val row = context.horizontal()
        private val main = Handler(Looper.getMainLooper())

        val view: View = context.vertical().apply {
            addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            })
            val activity = context as? MainActivity
            addView(context.horizontal().apply {
                setPadding(0, context.dp(8), 0, 0)
                addView(context.button("Галерея", outlined = true) { activity?.pickPhotos { importAll(it) } }.weight())
                addView(View(context).apply { layoutParams = LinearLayout.LayoutParams(context.dp(10), 1) })
                addView(context.button("Камера", outlined = true) { activity?.takePhoto { importAll(it) } }.weight())
            })
        }

        init { refresh() }

        val photos: List<String> get() = current.toList()

        private fun refresh() {
            row.removeAllViews()
            row.visibility = if (current.isEmpty()) View.GONE else View.VISIBLE
            current.forEach { n ->
                row.addView(thumbView(context, n, 72) {
                    PlannerPhotos.view(context, n) {
                        current.remove(n)
                        if (n in added) { added.remove(n); file(context, n).delete() } else removed += n
                        refresh()
                    }
                })
            }
        }

        private fun importAll(uris: List<Uri>) {
            if (uris.isEmpty()) return
            val app = context.applicationContext
            Thread {
                val names = uris.mapNotNull { import(app, it) }
                main.post { current += names; added += names; refresh() }
            }.start()
        }

        /** Запись сохранена: убранные фото удаляем окончательно. */
        fun commit() { delete(context, removed); removed.clear(); added.clear() }

        /** Запись не сохранили: новые снимки не нужны. */
        fun discard() { delete(context, added); added.clear() }
    }
}
