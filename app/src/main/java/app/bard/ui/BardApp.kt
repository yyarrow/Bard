package app.bard.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.provider.Settings
import app.bard.journal.Journal
import app.bard.poem.ApiKeyStore
import app.bard.poem.Poem
import app.bard.poem.PoemFinder
import app.bard.ui.theme.Ink
import app.bard.ui.theme.Kai
import app.bard.ui.theme.Paper
import app.bard.ui.theme.PaperDim
import app.bard.ui.theme.Sand
import app.bard.poem.ApiException
import app.bard.util.rotate
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

private sealed interface Stage {
    data object Camera : Stage
    data class Loading(val photo: Bitmap) : Stage
    data class Result(val photo: Bitmap, val poem: Poem) : Stage
    data class Failed(val photo: Bitmap, val message: String, val needKey: Boolean) : Stage
    data object Book : Stage
    data class Page(val entry: Journal.Entry) : Stage
    data object Gallery : Stage
}

private val MOCK_POEM = Poem(
    title = "山居秋暝",
    dynasty = "唐",
    author = "王维",
    lines = listOf("空山新雨后", "天气晚来秋", "明月松间照", "清泉石上流"),
    reason = "雨后新晴的暮色，正合王摩诘的清空。",
    excerpt = true,
)

@Composable
fun BardApp(mockPoem: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stage by remember { mutableStateOf<Stage>(Stage.Camera) }
    var showKeyDialog by rememberSaveable { mutableStateOf(false) }
    val usedTitles = remember { mutableListOf<String>() }
    // 备胎候选：主请求验剩的 + 批量补货的，换一首/切心境直接消费，不等网络
    val spares = remember { mutableStateListOf<Poem>() }
    var batchRequested by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var batchJob by remember { mutableStateOf<Job?>(null) }

    fun deviceId(): String = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ANDROID_ID,
    ) ?: "anon"

    fun rememberSpares(poems: List<Poem>) {
        val known = (usedTitles + spares.map { it.title }).toMutableSet()
        poems.forEach { if (known.add(it.title)) spares.add(it) }
    }

    fun seek(photo: Bitmap, exclude: List<String>, want: Int = 1) {
        job?.cancel()
        job = scope.launch {
            stage = Stage.Loading(photo)
            runCatching {
                if (mockPoem) {
                    kotlinx.coroutines.delay(1200)
                    listOf(MOCK_POEM)
                } else {
                    PoemFinder.find(
                        photo, ApiKeyStore.overrideValue(context), deviceId(), exclude,
                        want = want,
                    )
                }
            }
                .onSuccess { poems ->
                    val first = poems.first()
                    usedTitles += first.title
                    // 并发的批量补货可能抢先把同一首入了池，展示前剔除，防止下次换一首复读
                    spares.removeAll { it.title == first.title }
                    rememberSpares(poems.drop(1))
                    stage = Stage.Result(photo, first)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@onFailure
                    stage = Stage.Failed(photo, friendlyMessage(e), needsKey(e))
                }
        }
    }

    /** 第一次按「换一首」触发的后台批量补货（8 首、心境各异），静默失败。 */
    fun prefetchBatch(photo: Bitmap) {
        // 直连模式（个人 key）是单诗接口，没有批量/心境语义，预取只会白烧用户的钱
        if (batchRequested || mockPoem || ApiKeyStore.overrideValue(context) != null) return
        batchRequested = true
        batchJob = scope.launch {
            runCatching {
                PoemFinder.find(
                    photo, ApiKeyStore.overrideValue(context), deviceId(),
                    excludeTitles = (usedTitles + spares.map { it.title }).distinct(),
                    want = 8,
                )
            }
                .onSuccess { rememberSpares(it) }
                .onFailure { android.util.Log.w("Bard", "batch prefetch failed", it) }
        }
    }

    /** 换一首：有没看过的备胎就零等待直出、顺手后台补货；池子空了就把批量请求
     *  本身当前台请求（一次调用既出主选又填池），绝不并发两个全图请求。
     *  池里可能有切心境退回来的已看诗，换一首必须跳过它们。 */
    fun another(s: Stage.Result) {
        val idx = spares.indexOfFirst { it.title !in usedTitles }
        if (idx >= 0) {
            val next = spares.removeAt(idx)
            usedTitles += next.title
            stage = Stage.Result(s.photo, next)
            prefetchBatch(s.photo)
        } else {
            batchRequested = true // 这次前台批量就是补货，后台不必再来一发
            seek(s.photo, usedTitles.toList(), want = 8)
        }
    }

    /** 切心境：取该心境的备胎；当前这首带心境才回池（心境章是它唯一的找回入口，
     *  无心境的回池只会变成死条目）。 */
    fun pickMood(s: Stage.Result, mood: String) {
        val idx = spares.indexOfFirst { it.mood == mood }
        if (idx < 0) return
        val chosen = spares.removeAt(idx)
        if (s.poem.mood.isNotBlank()) spares.add(s.poem)
        if (chosen.title !in usedTitles) usedTitles += chosen.title
        stage = Stage.Result(s.photo, chosen)
    }

    fun backToCamera() {
        job?.cancel()
        // 放弃这张照片就没必要继续补货了——批量请求带着整张图，白烧流量和配额
        batchJob?.cancel()
        batchRequested = false
        stage = Stage.Camera
    }

    if (stage != Stage.Camera) {
        BackHandler {
            stage = when (stage) {
                is Stage.Page, Stage.Gallery -> Stage.Book
                else -> {
                    job?.cancel()
                    batchJob?.cancel()
                    batchRequested = false
                    Stage.Camera
                }
            }
        }
    }

    Crossfade(targetState = stage, label = "stage") { s ->
        when (s) {
            is Stage.Camera -> CameraScreen(
                onCaptured = { photo ->
                    usedTitles.clear()
                    spares.clear()
                    batchRequested = false
                    batchJob?.cancel()
                    seek(photo, emptyList())
                },
                onOpenKeySettings = { showKeyDialog = true },
                onOpenJournal = { stage = Stage.Book },
            )

            is Stage.Loading -> LoadingScreen(photo = s.photo, onCancel = ::backToCamera)

            is Stage.Result -> ResultScreen(
                photo = s.photo,
                poem = s.poem,
                onRetake = ::backToCamera,
                onAnother = { another(s) },
                onRotate = { stage = Stage.Result(s.photo.rotate(90), s.poem) },
                moods = spares.mapNotNull { it.mood.ifBlank { null } }.distinct(),
                onMood = { pickMood(s, it) },
            )

            is Stage.Book -> JournalScreen(
                onOpenPage = { stage = Stage.Page(it) },
                onOpenGallery = { stage = Stage.Gallery },
                onBack = ::backToCamera,
            )

            is Stage.Gallery -> GalleryScreen(onBack = { stage = Stage.Book })

            is Stage.Page -> PageViewerScreen(
                entry = s.entry,
                onDeleted = { stage = Stage.Book },
                onBack = { stage = Stage.Book },
            )

            is Stage.Failed -> FailedScreen(
                message = s.message,
                onRetry = { seek(s.photo, usedTitles.toList()) },
                onRetake = ::backToCamera,
                onSetKey = if (s.needKey) {
                    { showKeyDialog = true }
                } else null,
            )
        }
    }

    if (showKeyDialog) {
        ApiKeyDialog(onDismiss = { showKeyDialog = false })
    }
}

@Composable
private fun FailedScreen(
    message: String,
    onRetry: () -> Unit,
    onRetake: () -> Unit,
    onSetKey: (() -> Unit)?,
) {
    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.padding(28.dp),
        ) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("唉", fontFamily = Kai, fontSize = 40.sp, color = Sand)
                Text(
                    message,
                    fontFamily = Kai, fontSize = 15.sp, color = PaperDim,
                    textAlign = TextAlign.Center,
                )
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                    Text("再试一次", fontFamily = Kai)
                }
                if (onSetKey != null) {
                    TextButton(onClick = onSetKey, modifier = Modifier.fillMaxWidth()) {
                        Text("去设置 API Key", fontFamily = Kai, color = Paper)
                    }
                }
                TextButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) {
                    Text("重拍", fontFamily = Kai, color = Sand)
                }
            }
        }
    }
}

@Composable
private fun ApiKeyDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var value by remember {
        mutableStateOf(ApiKeyStore.overrideValue(context) ?: "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenRouter API Key", fontFamily = Kai) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "默认走官方通道，无需配置。想用自己的 OpenRouter Key 直连再填这里，Key 只保存在本机。",
                    fontSize = 13.sp, color = PaperDim,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text("sk-or-…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                ApiKeyStore.setOverride(context, value)
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

private fun friendlyMessage(e: Throwable): String = when {
    e is ApiException && (e.code == 401 || e.code == 403) ->
        "应用没通过校验，可能需要更新版本；\n也可以在设置里填自己的 OpenRouter Key 直连。"
    e is ApiException && e.code == 429 -> "今天的份额用完啦，明日再来。"
    e is ApiException -> "服务出了点状况（${e.code}）：${e.message?.take(80)}"
    e is IOException -> "网络不太通畅，请检查网络后再试。"
    e is JSONException || e is IllegalArgumentException -> "这次没读懂照片的意思，再试一次？"
    else -> "出了点问题：${e.message?.take(100) ?: e.javaClass.simpleName}"
}

private fun needsKey(e: Throwable): Boolean =
    e is ApiException && (e.code == 401 || e.code == 403)
