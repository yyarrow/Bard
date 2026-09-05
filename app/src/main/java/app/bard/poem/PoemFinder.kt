package app.bard.poem

import android.graphics.Bitmap
import android.util.Base64
import app.bard.BuildConfig
import app.bard.util.downscale
import app.bard.util.toJpegBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(val code: Int, message: String) : Exception(message)

/**
 * 找诗。默认走自家 Vercel 代理（/api/poem，OpenRouter key 在服务端）；
 * 用户填了自己的 key 时直连 OpenRouter。
 */
object PoemFinder {

    private const val MODEL = "google/gemini-3.8-flash"
    private const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
    private val JSON = "application/json".toMediaType()

    private val SYSTEM_PROMPT = """
        你是一位学养深厚的诗词编辑。用户给你一张照片，你从真实存在的中国古典诗词
        （以唐诗宋词为主，也可选汉魏六朝与元明清名篇）中，选出与照片的景物、光线、
        季节、时辰、情绪最契合的一首。

        要求：
        - 必须是真实存在的原文，一字不差，绝不自己创作、拼接或改写。
        - 优先选意境贴切的；但宁可选名篇，也不要选你记不准原文的生僻之作。
        - 若全诗较长（长调词、古风），取其中最契合的连续二至四句，excerpt 设为 true。
        - lines：把选出的内容按标点切成短句，每个短句一个元素，不含任何标点，
          保持原文顺序，总数控制在 2 到 8 个。
        - reason：一句话（三十字以内）说明为何契合此景，语气清雅，不要用「这张照片」开头。

        只输出 JSON，不要任何其他文字，格式如下：
        {"title":"静夜思","dynasty":"唐","author":"李白","lines":["床前明月光","疑是地上霜","举头望明月","低头思故乡"],"excerpt":false,"reason":"…"}
    """.trimIndent()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /** 返回按契合度排序的候选（首位主选，其余备胎）。want>=4 走批量心境补货。 */
    suspend fun find(
        photo: Bitmap,
        personalKey: String?,
        deviceId: String,
        excludeTitles: List<String>,
        want: Int = 1,
    ): List<Poem> = withContext(Dispatchers.IO) {
        val jpeg = photo.downscale(1024).toJpegBytes(quality = 80)
        val dataUrl = "data:image/jpeg;base64," +
                Base64.encodeToString(jpeg, Base64.NO_WRAP)

        val direct = !personalKey.isNullOrBlank()
        val request =
            if (direct) directRequest(dataUrl, personalKey!!, excludeTitles)
            else proxyRequest(dataUrl, deviceId, excludeTitles, want)

        val (code, text) = await(http.newCall(request))
        android.util.Log.d("Bard", "poem HTTP $code: ${text.take(400)}")
        if (code !in 200..299) {
            throw ApiException(code, errorDetail(text))
        }

        if (direct) {
            val content = JSONObject(text)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
            listOf(Poem.fromJson(content))
        } else {
            Poem.listFromJson(text)
        }
    }

    private fun proxyRequest(
        dataUrl: String,
        deviceId: String,
        excludeTitles: List<String>,
        want: Int,
    ): Request {
        val body = JSONObject().apply {
            put("image", dataUrl)
            put("exclude", JSONArray(excludeTitles))
            if (want > 1) put("want", want)
        }
        return Request.Builder()
            .url(BuildConfig.BARD_API_BASE + "/api/poem")
            .header("X-Bard-Key", BuildConfig.BARD_APP_SECRET)
            .header("X-Bard-Device", deviceId)
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    private fun directRequest(
        dataUrl: String,
        apiKey: String,
        excludeTitles: List<String>,
    ): Request {
        val ask = buildString {
            append("为这张照片选一首契合此情此景的诗词。")
            if (excludeTitles.isNotEmpty()) {
                append("这些已经选过，请换别的：${excludeTitles.joinToString("、")}。")
            }
        }
        val body = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 2000)
            put("response_format", JSONObject().put("type", "json_object"))
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().put("url", dataUrl))
                        })
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", ask)
                        })
                    })
                })
            })
        }
        return Request.Builder()
            .url(OPENROUTER)
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "Bard")
            .post(body.toString().toRequestBody(JSON))
            .build()
    }

    /** 兼容代理 {"error":"…"} 与 OpenRouter {"error":{"message":"…"}} 两种错误格式。 */
    private fun errorDetail(text: String): String = runCatching {
        val err = JSONObject(text).get("error")
        if (err is JSONObject) err.getString("message") else err.toString()
    }.getOrDefault(text.take(200))

    /** enqueue + suspendCancellableCoroutine，取消时中断请求。 */
    private suspend fun await(call: Call): Pair<Int, String> =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val text = response.use { it.body?.string().orEmpty() }
                    if (cont.isActive) cont.resume(response.code to text)
                }
            })
        }
}
