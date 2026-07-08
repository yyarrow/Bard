package app.bard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bard.journal.Journal
import app.bard.ui.theme.Ink
import app.bard.ui.theme.Kai
import app.bard.ui.theme.Paper
import app.bard.ui.theme.PaperDim
import app.bard.ui.theme.Sand
import app.bard.util.saveToGallery
import app.bard.util.shareImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 手账时间轴：一天一页，新的在前。 */
@Composable
fun JournalScreen(
    onOpenPage: (Journal.Entry) -> Unit,
    onOpenGallery: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val entries = remember { Journal.list(context) }

    Column(Modifier.fillMaxSize().background(Ink).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Paper)
            }
            Text("拾诗手账", fontFamily = Kai, fontSize = 20.sp, color = Paper)
            Spacer(Modifier.weight(1f))
            Text(
                if (entries.isEmpty()) "" else "${entries.size} 页",
                fontFamily = Kai, fontSize = 13.sp, color = Sand,
            )
            IconButton(onClick = onOpenGallery) {
                Icon(Icons.Outlined.AutoAwesomeMosaic, "图鉴", tint = Sand)
            }
        }

        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有一页。\n拍一张照片，配上一首诗，点「入册」。",
                    fontFamily = Kai, fontSize = 15.sp, color = PaperDim,
                    lineHeight = 26.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, top = 6.dp, bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                items(entries, key = { it.id }) { entry ->
                    JournalCard(entry) { onOpenPage(entry) }
                }
            }
        }
    }
}

@Composable
private fun JournalCard(entry: Journal.Entry, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumb by produceState<Bitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) { Journal.loadBaked(context, entry.id, maxDim = 800) }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column {
            thumb?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = entry.poem.title,
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth().clip(
                        RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    ),
                )
            }
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    SimpleDateFormat("M月d日", Locale.CHINA).format(Date(entry.createdAt)),
                    fontFamily = Kai, fontSize = 13.sp, color = Sand,
                )
                Text(
                    "《${entry.poem.title}》 ${entry.poem.author}",
                    fontFamily = Kai, fontSize = 13.sp, color = PaperDim,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
    }
}

/** 单页查看：成品图 + 保存/分享/删除。 */
@Composable
fun PageViewerScreen(
    entry: Journal.Entry,
    onDeleted: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    val baked by produceState<Bitmap?>(null, entry.id) {
        value = withContext(Dispatchers.IO) { Journal.loadBaked(context, entry.id) }
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Paper)
                }
                Text(
                    SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(entry.createdAt)),
                    fontFamily = Kai, fontSize = 15.sp, color = Paper,
                )
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().padding(14.dp),
                contentAlignment = Alignment.Center,
            ) {
                baked?.let {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = entry.poem.title,
                        modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            if (entry.poem.reason.isNotBlank()) {
                Text(
                    entry.poem.reason,
                    fontFamily = Kai, fontSize = 13.sp, color = PaperDim,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                ActionChip(Icons.Outlined.Download, "保存") {
                    baked?.let { bmp ->
                        scope.launch {
                            val ok = withContext(Dispatchers.Default) { saveToGallery(context, bmp) }
                            snackbar.showSnackbar(if (ok) "已保存到相册" else "保存失败")
                        }
                    }
                }
                ActionChip(Icons.Outlined.Share, "分享") {
                    baked?.let { bmp ->
                        scope.launch(Dispatchers.Default) { shareImage(context, bmp) }
                    }
                }
                ActionChip(Icons.Outlined.DeleteOutline, "删除") { confirmDelete = true }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("撕掉这一页？", fontFamily = Kai) },
            text = { Text("《${entry.poem.title}》和这张照片会从手账里删除。", fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    Journal.delete(context, entry.id)
                    confirmDelete = false
                    onDeleted()
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("留着") }
            },
        )
    }
}

@Composable
private fun ActionChip(
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
                modifier = Modifier.padding(12.dp).size(22.dp),
            )
        }
        Text(label, fontFamily = Kai, fontSize = 12.sp, color = PaperDim)
    }
}
