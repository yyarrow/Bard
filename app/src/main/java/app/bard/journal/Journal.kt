package app.bard.journal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.bard.poem.Poem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 手账存储：本地优先、无账号。每页一个目录
 * filesDir/journal/<id>/{photo.jpg 原图, baked.jpg 成品, page.json 元数据}，
 * 便于备份迁移，也没有数据库迁移负担。列表在内存缓存，量级（数百页）足够。
 */
object Journal {

    data class Entry(
        val id: String,
        val createdAt: Long,
        val poem: Poem,
        val anchorX: Float,
        val anchorY: Float,
        val scale: Float,
        val horizontal: Boolean,
        val note: String = "",
    )

    private var cache: MutableList<Entry>? = null

    private fun root(context: Context): File =
        File(context.filesDir, "journal").apply { mkdirs() }

    private fun dir(context: Context, id: String): File = File(root(context), id)

    /** 收录一页。photo 为原图、baked 为当前排版的成品图。返回新条目。 */
    fun add(
        context: Context,
        photo: Bitmap,
        baked: Bitmap,
        poem: Poem,
        anchorX: Float,
        anchorY: Float,
        scale: Float,
        horizontal: Boolean,
    ): Entry {
        val now = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now)) +
                "-" + (now % 1000)
        val d = dir(context, id).apply { mkdirs() }
        d.resolve("photo.jpg").outputStream().use {
            photo.compress(Bitmap.CompressFormat.JPEG, 88, it)
        }
        d.resolve("baked.jpg").outputStream().use {
            baked.compress(Bitmap.CompressFormat.JPEG, 90, it)
        }
        val entry = Entry(id, now, poem, anchorX, anchorY, scale, horizontal)
        d.resolve("page.json").writeText(toJson(entry).toString())
        cache = null // 新页已落盘，作废缓存待重读，避免与磁盘扫描重复
        return entry
    }

    /** 全部条目，新的在前。 */
    fun list(context: Context): List<Entry> {
        cache?.let { return it }
        val entries = root(context).listFiles { f -> f.isDirectory }
            ?.mapNotNull { d ->
                runCatching {
                    fromJson(JSONObject(d.resolve("page.json").readText()))
                }.getOrNull()
            }
            ?.sortedByDescending { it.createdAt }
            ?.toMutableList()
            ?: mutableListOf()
        cache = entries
        return entries
    }

    fun delete(context: Context, id: String) {
        dir(context, id).deleteRecursively()
        cache?.removeAll { it.id == id }
    }

    fun bakedFile(context: Context, id: String): File = dir(context, id).resolve("baked.jpg")

    /** 连续记录天数：从今天（或昨天）往回数有记录的连续日子。 */
    fun streakDays(context: Context): Int {
        val zone = java.time.ZoneId.systemDefault()
        val days = list(context)
            .map { java.time.Instant.ofEpochMilli(it.createdAt).atZone(zone).toLocalDate() }
            .toSortedSet(compareByDescending { it })
        if (days.isEmpty()) return 0
        val today = java.time.LocalDate.now(zone)
        var cursor = when (days.first()) {
            today -> today
            today.minusDays(1) -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (cursor in days) {
            streak++
            cursor = cursor.minusDays(1)
        }
        return streak
    }

    /** 成品图，按需采样解码到不超过 maxDim。 */
    fun loadBaked(context: Context, id: String, maxDim: Int = 0): Bitmap? {
        val f = bakedFile(context, id)
        if (!f.exists()) return null
        if (maxDim <= 0) return BitmapFactory.decodeFile(f.path)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        return BitmapFactory.decodeFile(
            f.path,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun toJson(e: Entry): JSONObject = JSONObject().apply {
        put("id", e.id)
        put("createdAt", e.createdAt)
        put("anchorX", e.anchorX.toDouble())
        put("anchorY", e.anchorY.toDouble())
        put("scale", e.scale.toDouble())
        put("horizontal", e.horizontal)
        put("note", e.note)
        put("poem", JSONObject().apply {
            put("title", e.poem.title)
            put("dynasty", e.poem.dynasty)
            put("author", e.poem.author)
            put("lines", JSONArray(e.poem.lines))
            put("reason", e.poem.reason)
            put("excerpt", e.poem.excerpt)
        })
    }

    private fun fromJson(o: JSONObject): Entry {
        val p = o.getJSONObject("poem")
        val linesArr = p.getJSONArray("lines")
        return Entry(
            id = o.getString("id"),
            createdAt = o.getLong("createdAt"),
            poem = Poem(
                title = p.getString("title"),
                dynasty = p.optString("dynasty"),
                author = p.optString("author"),
                lines = (0 until linesArr.length()).map { linesArr.getString(it) },
                reason = p.optString("reason"),
                excerpt = p.optBoolean("excerpt", false),
            ),
            anchorX = o.getDouble("anchorX").toFloat(),
            anchorY = o.getDouble("anchorY").toFloat(),
            scale = o.getDouble("scale").toFloat(),
            horizontal = o.getBoolean("horizontal"),
            note = o.optString("note"),
        )
    }
}
