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
    var job by remember { mutableStateOf<Job?>(null) }

    fun seek(photo: Bitmap, exclude: List<String>) {
        job?.cancel()
        job = scope.launch {
            stage = Stage.Loading(photo)
            runCatching {
                if (mockPoem) {
                    kotlinx.coroutines.delay(1200)
                    MOCK_POEM
                } else {
                    val deviceId = Settings.Secure.getString(
                        context.contentResolver, Settings.Secure.ANDROID_ID,
                    ) ?: "anon"
                    PoemFinder.find(photo, ApiKeyStore.overrideValue(context), deviceId, exclude)
                }
            }
                .onSuccess { poem ->
                    usedTitles += poem.title
                    stage = Stage.Result(photo, poem)
                }
                .onFailure { e ->
                    if (e is kotlinx.coroutines.CancellationException) return@onFailure
                    stage = Stage.Failed(photo, friendlyMessage(e), needsKey(e))
                }
        }
    }

    fun backToCamera() {
        job?.cancel()
        stage = Stage.Camera
    }

    if (stage != Stage.Camera) {
        BackHandler {
            stage = when (stage) {
                is Stage.Page, Stage.Gallery -> Stage.Book
                else -> {
                    job?.cancel()
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
                onAnother = { seek(s.photo, usedTitles.toList()) },
                onRotate = { stage = Stage.Result(s.photo.rotate(90), s.poem) },
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
