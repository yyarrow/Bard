package app.bard.poem

import android.content.Context
import org.json.JSONArray
import java.time.LocalDate

/**
 * 今日一诗：从打包进 assets 的精选库（daily.json，构建期经语料库核验的名篇）
 * 按月份过滤后以日期确定性取一首——全离线，不耗 API。
 */
object DailyPoem {

    data class Daily(
        val title: String,
        val dynasty: String,
        val author: String,
        val lines: List<String>,
        val months: List<Int>,
    )

    private var all: List<Daily>? = null

    private fun load(context: Context): List<Daily> {
        all?.let { return it }
        val loaded = runCatching {
            val text = context.assets.open("daily.json").bufferedReader().readText()
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val lines = o.getJSONArray("lines")
                val months = o.getJSONArray("months")
                Daily(
                    title = o.getString("title"),
                    dynasty = o.optString("dynasty"),
                    author = o.optString("author"),
                    lines = (0 until lines.length()).map { lines.getString(it) },
                    months = (0 until months.length()).map { months.getInt(it) },
                )
            }
        }.getOrDefault(emptyList())
        all = loaded
        return loaded
    }

    fun today(context: Context): Daily? {
        val poems = load(context)
        if (poems.isEmpty()) return null
        val date = LocalDate.now()
        val pool = poems.filter { date.monthValue in it.months }.ifEmpty { poems }
        return pool[(date.year * 366 + date.dayOfYear) % pool.size]
    }
}
