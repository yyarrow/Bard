package app.bard.poem

import org.json.JSONObject

data class Poem(
    val title: String,
    val dynasty: String,
    val author: String,
    val lines: List<String>,
    val reason: String,
    val excerpt: Boolean,
    val mood: String = "",
) {
    companion object {
        fun fromJsonObject(obj: JSONObject): Poem {
            val linesArr = obj.getJSONArray("lines")
            val lines = (0 until linesArr.length())
                .map { linesArr.getString(it).trim() }
                .filter { it.isNotEmpty() }
            require(lines.isNotEmpty()) { "诗句为空" }
            return Poem(
                title = obj.getString("title").trim(),
                dynasty = obj.optString("dynasty").trim(),
                author = obj.getString("author").trim(),
                lines = lines,
                reason = obj.optString("reason").trim(),
                excerpt = obj.optBoolean("excerpt", false),
                mood = obj.optString("mood").trim(),
            )
        }

        /** 容忍模型偶尔在 JSON 外包一层说明或代码块。 */
        fun fromJson(raw: String): Poem {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start in 0 until end) { "响应里没有 JSON：$raw" }
            return fromJsonObject(JSONObject(raw.substring(start, end + 1)))
        }

        /** 解析代理响应：顶层主选 + alternates 备胎，首位是主选。 */
        fun listFromJson(raw: String): List<Poem> {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            require(start in 0 until end) { "响应里没有 JSON：$raw" }
            val obj = JSONObject(raw.substring(start, end + 1))
            val result = mutableListOf(fromJsonObject(obj))
            val alts = obj.optJSONArray("alternates")
            if (alts != null) {
                for (i in 0 until alts.length()) {
                    runCatching { fromJsonObject(alts.getJSONObject(i)) }
                        .onSuccess { result.add(it) }
                }
            }
            return result
        }
    }
}
