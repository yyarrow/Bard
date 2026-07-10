package app.bard.ui

import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.BookmarkAdded
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import app.bard.R
import app.bard.journal.Journal
import app.bard.poem.Poem
import app.bard.render.PoemPainter
import app.bard.ui.theme.Cinnabar
import app.bard.ui.theme.Ink
import app.bard.ui.theme.Kai
import app.bard.ui.theme.Paper
import app.bard.ui.theme.PaperDim
import app.bard.ui.theme.Sand
import app.bard.util.Almanac
import app.bard.util.saveToGallery
import app.bard.util.shareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

private const val SCALE_MIN = 0.45f
private const val SCALE_MAX = 1.5f

@Composable
fun ResultScreen(
    photo: Bitmap,
    poem: Poem,
    onRetake: () -> Unit,
    onAnother: () -> Unit,
    onRotate: () -> Unit,
    moods: List<String> = emptyList(),
    onMood: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val density = LocalDensity.current

    val typeface = remember {
        runCatching { ResourcesCompat.getFont(context, R.font.lxgw) }.getOrNull()
            ?: Typeface.create("serif", Typeface.NORMAL)
    }

    // 排版方向与字号缩放（缩放只做位图变换，不重排，拖动/捏合都丝滑）
    var horizontal by remember(poem) { mutableStateOf(false) }
    var userScale by remember(poem) { mutableFloatStateOf(1f) }
    val layout = remember(poem, photo, horizontal) {
        PoemPainter.measure(photo.width, photo.height, poem, horizontal)
    }

    // 题字位置（可用空间内的比例坐标），默认右上
    var anchor by remember(poem) { mutableStateOf(Offset(0.94f, 0.05f)) }
    // 手势停下来后再采样底色、决定墨色/纸白，避免操作中闪烁
    var settled by remember(poem) { mutableStateOf(Offset(0.94f, 0.05f) to 1f) }
    LaunchedEffect(anchor, userScale, layout) {
        delay(250)
        settled = anchor to userScale
    }

    // 长按诗句浮现出处，片刻后自行隐去
    var showSource by remember(poem) { mutableStateOf(false) }
    LaunchedEffect(showSource) {
        if (showSource) {
            delay(3200)
            showSource = false
        }
    }

    val overlay = remember(layout, settled) {
        val (fx, fy) = settled.first
        val s = settled.second
        val (x, y) = PoemPainter.overlayPos(photo.width, photo.height, layout, fx, fy, s)
        val bright = PoemPainter.regionIsBright(
            photo,
            Rect(
                x.toInt(), y.toInt(),
                (x + layout.width * s).toInt(), (y + layout.height * s).toInt(),
            ),
        )
        PoemPainter.renderOverlay(layout, typeface, darkInk = bright)
    }

    fun bakeNow(): Bitmap {
        val now = System.currentTimeMillis()
        val caption = "${Almanac.formalDate(now)} · ${Almanac.solarTerm(now)}"
        return PoemPainter.bake(
            photo, overlay, layout, anchor.x, anchor.y, userScale,
            caption = caption, captionTypeface = typeface,
        )
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            BoxWithConstraints(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                val cw = constraints.maxWidth.toFloat()
                val ch = constraints.maxHeight.toFloat()
                val dispScale = min(cw / photo.width, ch / photo.height)
                val dispW = photo.width * dispScale
                val dispH = photo.height * dispScale
                val ovW = layout.width * dispScale * userScale
                val ovH = layout.height * dispScale * userScale
                val freeW = rememberUpdatedState((dispW - ovW).coerceAtLeast(1f))
                val freeH = rememberUpdatedState((dispH - ovH).coerceAtLeast(1f))

                Box(
                    Modifier
                        .size(with(density) { dispW.toDp() }, with(density) { dispH.toDp() })
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Image(
                        bitmap = photo.asImageBitmap(),
                        contentDescription = "照片",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Image(
                        bitmap = overlay.asImageBitmap(),
                        contentDescription = "诗句（拖动移位，双指缩放）",
                        modifier = Modifier
                            .offset {
                                IntOffset(
                                    (anchor.x * freeW.value).roundToInt(),
                                    (anchor.y * freeH.value).roundToInt(),
                                )
                            }
                            .size(with(density) { ovW.toDp() }, with(density) { ovH.toDp() })
                            .pointerInput(layout) {
                                detectTransformGestures { _, pan, zoom, _ ->
                                    userScale = (userScale * zoom)
                                        .coerceIn(SCALE_MIN, SCALE_MAX)
                                    anchor = Offset(
                                        (anchor.x + pan.x / freeW.value).coerceIn(0f, 1f),
                                        (anchor.y + pan.y / freeH.value).coerceIn(0f, 1f),
                                    )
                                }
                            }
                            .pointerInput(poem) {
                                detectTapGestures(onLongPress = { showSource = true })
                            },
                    )

                    androidx.compose.animation.AnimatedVisibility(
                        visible = showSource,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 16.dp),
                    ) {
                        Surface(
                            color = Ink.copy(alpha = 0.72f),
                            contentColor = Paper,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                buildString {
                                    append("《${poem.title}》")
                                    val by = listOf(poem.dynasty, poem.author)
                                        .filter { it.isNotBlank() }.joinToString(" · ")
                                    if (by.isNotEmpty()) append("  $by")
                                },
                                fontFamily = Kai, fontSize = 13.sp,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }

            // 心境印章：备胎里有不同心境时才出现，点一下秒切（不发请求）
            val moodChoices = remember(moods, poem) {
                (listOf(poem.mood).filter { it.isNotBlank() } + moods).distinct()
            }
            if (moodChoices.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    moodChoices.forEach { m ->
                        val current = m == poem.mood
                        Surface(
                            onClick = { if (!current) onMood(m) },
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            border = if (current) BorderStroke(1.dp, Cinnabar) else null,
                        ) {
                            Text(
                                m, fontFamily = Kai, fontSize = 12.sp,
                                color = if (current) Cinnabar else PaperDim,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
            }

            // 排版控制：竖/横切换 + 字号滑杆
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(
                    onClick = { horizontal = !horizontal },
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        if (horizontal) "横排" else "竖排",
                        fontFamily = Kai, fontSize = 13.sp, color = Paper,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                Text(
                    "A", fontFamily = Kai, fontSize = 11.sp, color = Sand,
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp),
                )
                Slider(
                    value = userScale,
                    onValueChange = { userScale = it },
                    valueRange = SCALE_MIN..SCALE_MAX,
                    colors = SliderDefaults.colors(
                        thumbColor = Paper,
                        activeTrackColor = Sand,
                        inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "A", fontFamily = Kai, fontSize = 17.sp, color = Sand,
                    modifier = Modifier.padding(start = 6.dp),
                )
                Surface(
                    onClick = onRotate,
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.padding(start = 10.dp),
                ) {
                    Icon(
                        Icons.Outlined.RotateRight, contentDescription = "旋转照片",
                        tint = Paper, modifier = Modifier.padding(7.dp).size(20.dp),
                    )
                }
            }

            Text(
                "拖动或双指缩放诗句 · 长按看出处",
                fontFamily = Kai, fontSize = 12.sp, color = Sand,
                modifier = Modifier.padding(horizontal = 18.dp),
            )

            if (poem.reason.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Text("按", fontFamily = Kai, fontSize = 13.sp, color = Cinnabar)
                        Text(
                            poem.reason,
                            fontFamily = Kai, fontSize = 13.sp, color = PaperDim,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
            }

            // 入册状态跟随当前这首诗（换一首后可再入）
            var journaledId by remember(poem) { mutableStateOf<String?>(null) }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ActionItem(
                    if (journaledId == null) Icons.Outlined.BookmarkAdd
                    else Icons.Outlined.BookmarkAdded,
                    if (journaledId == null) "入册" else "已入册",
                ) {
                    if (journaledId == null) {
                        scope.launch {
                            val entry = withContext(Dispatchers.Default) {
                                Journal.add(
                                    context, photo, bakeNow(), poem,
                                    anchor.x, anchor.y, userScale, horizontal,
                                )
                            }
                            journaledId = entry.id
                            snackbar.showSnackbar("已入册 · 手账第 ${Journal.list(context).size} 页")
                        }
                    }
                }
                ActionItem(Icons.Outlined.PhotoCamera, "重拍", onRetake)
                ActionItem(Icons.Outlined.Refresh, "换一首", onAnother)
                ActionItem(Icons.Outlined.Download, "保存") {
                    scope.launch {
                        val ok = withContext(Dispatchers.Default) {
                            saveToGallery(context, bakeNow())
                        }
                        snackbar.showSnackbar(if (ok) "已保存到相册 · Pictures/拾诗" else "保存失败")
                    }
                }
                ActionItem(Icons.Outlined.Share, "分享") {
                    scope.launch(Dispatchers.Default) { shareImage(context, bakeNow()) }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceVariant,
        ) {
            Icon(
                icon, contentDescription = label, tint = Paper,
                modifier = Modifier.padding(14.dp).size(24.dp),
            )
        }
        Text(label, fontFamily = Kai, fontSize = 12.sp, color = PaperDim)
    }
}
