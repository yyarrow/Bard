package app.bard.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.view.OrientationEventListener
import android.view.Surface
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Cameraswitch
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import app.bard.ui.theme.Kai
import app.bard.ui.theme.Paper
import app.bard.ui.theme.Sand
import app.bard.journal.Journal
import app.bard.poem.DailyPoem
import app.bard.util.Almanac
import app.bard.util.decodeUri
import app.bard.util.downscale
import app.bard.util.rotate
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
fun CameraScreen(
    onCaptured: (Bitmap) -> Unit,
    onOpenKeySettings: () -> Unit,
    onOpenJournal: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        android.util.Log.d("Bard", "gallery pick uri=$uri")
        if (uri != null) {
            val bmp = decodeUri(context, uri, maxDim = 1600)
            android.util.Log.d("Bard", "gallery decode -> ${bmp?.width}x${bmp?.height}")
            if (bmp != null) {
                onCaptured(bmp)
            } else {
                Toast.makeText(context, "没能读出这张图片，换一张试试", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var lensFacing by remember { mutableStateOf(CameraSelector.LENS_FACING_BACK) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // 界面锁竖屏，但用户可能横着拍：跟随物理方向更新 targetRotation，
    // 让 rotationDegrees 把照片转正
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                imageCapture.targetRotation = when {
                    orientation >= 315 || orientation < 45 -> Surface.ROTATION_0
                    orientation < 135 -> Surface.ROTATION_270
                    orientation < 225 -> Surface.ROTATION_180
                    else -> Surface.ROTATION_90
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            }
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            LaunchedEffect(lensFacing) {
                val provider = awaitCameraProvider(context)
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.Builder().requireLensFacing(lensFacing).build(),
                    preview,
                    imageCapture,
                )
            }
            DisposableEffect(Unit) {
                onDispose {
                    runCatching {
                        ProcessCameraProvider.getInstance(context).get().unbindAll()
                    }
                }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("拾诗需要相机，才能看见你眼前的景色", color = Paper, textAlign = TextAlign.Center)
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限")
                }
                TextButton(onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Text("或从相册选一张", color = Sand)
                }
            }
        }

        // 顶部题字
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("拾 诗", fontFamily = Kai, fontSize = 26.sp, color = Paper)
            Text(
                "拍下此刻 · 寻一句诗",
                fontFamily = Kai, fontSize = 12.sp, color = Sand,
                modifier = Modifier.padding(top = 2.dp),
            )
            val today = remember { System.currentTimeMillis() }
            Text(
                remember(today) {
                    java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA)
                        .format(java.util.Date(today)) +
                        " · ${Almanac.solarTerm(today)} · 农历${Almanac.lunarDate(today)}"
                },
                fontFamily = Kai, fontSize = 11.sp, color = Sand,
                modifier = Modifier.padding(top = 6.dp),
            )

            // 今日一诗（离线精选库）+ 连续记录
            val daily = remember { DailyPoem.today(context) }
            val streak = remember { Journal.streakDays(context) }
            var showDaily by remember { mutableStateOf(false) }
            if (daily != null) {
                Surface(
                    onClick = { showDaily = true },
                    shape = RoundedCornerShape(50),
                    color = Color.Black.copy(alpha = 0.35f),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    Text(
                        "今日一诗 · ${daily.title}" +
                            if (streak > 1) "　已连记 $streak 天" else "",
                        fontFamily = Kai, fontSize = 12.sp, color = Paper,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                }
            }
            if (showDaily && daily != null) {
                AlertDialog(
                    onDismissRequest = { showDaily = false },
                    title = {
                        Text("${daily.title}", fontFamily = Kai)
                    },
                    text = {
                        Column {
                            Text(
                                listOf(daily.dynasty, daily.author)
                                    .filter { it.isNotBlank() }.joinToString(" · "),
                                fontFamily = Kai, fontSize = 13.sp, color = Sand,
                            )
                            Text(
                                daily.lines.joinToString("\n"),
                                fontFamily = Kai, fontSize = 17.sp,
                                lineHeight = 30.sp,
                                modifier = Modifier.padding(top = 12.dp),
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showDaily = false }) {
                            Text("拍一张应景的", fontFamily = Kai)
                        }
                    },
                )
            }
        }
        IconButton(
            onClick = onOpenKeySettings,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(6.dp),
        ) {
            Icon(Icons.Outlined.Key, contentDescription = "设置 API Key", tint = Sand)
        }
        IconButton(
            onClick = onOpenJournal,
            modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, contentDescription = "手账", tint = Paper)
        }

        // 底部操作条
        if (hasPermission) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }) {
                    Icon(
                        Icons.Outlined.Image, contentDescription = "相册",
                        tint = Paper, modifier = Modifier.size(30.dp),
                    )
                }

                ShutterButton {
                    imageCapture.takePicture(
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageCapturedCallback() {
                            override fun onCaptureSuccess(image: ImageProxy) {
                                val rotation = image.imageInfo.rotationDegrees
                                val bitmap = image.toBitmap()
                                image.close()
                                onCaptured(bitmap.rotate(rotation).downscale(1600))
                            }

                            override fun onError(exception: ImageCaptureException) {
                                exception.printStackTrace()
                                Toast.makeText(context, "相机还没准备好，再按一次", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }

                IconButton(onClick = {
                    lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                        CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                }) {
                    Icon(
                        Icons.Outlined.Cameraswitch, contentDescription = "切换镜头",
                        tint = Paper, modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

private suspend fun awaitCameraProvider(context: android.content.Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                runCatching { future.get() }
                    .onSuccess { if (cont.isActive) cont.resume(it) }
                    .onFailure { if (cont.isActive) cont.resumeWithException(it) }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

@Composable
private fun ShutterButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, label = "shutter")
    Box(
        Modifier
            .size(76.dp)
            .scale(scale)
            .clip(CircleShape)
            .border(3.dp, Paper, CircleShape)
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = onClick,
            interactionSource = interaction,
            shape = CircleShape,
            color = Paper,
            modifier = Modifier.fillMaxSize(),
        ) {}
    }
}
