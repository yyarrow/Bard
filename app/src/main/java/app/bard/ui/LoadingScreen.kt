package app.bard.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bard.ui.theme.Cinnabar
import app.bard.ui.theme.Ink
import app.bard.ui.theme.Kai
import app.bard.ui.theme.PaperDim
import app.bard.ui.theme.Sand
import kotlinx.coroutines.delay

private val HINTS = listOf(
    "正在端详光影…",
    "翻检唐诗三百首…",
    "问了问李白…",
    "又问了问苏东坡…",
    "在宋词里挑一句…",
    "磨墨，铺纸…",
)

@Composable
fun LoadingScreen(photo: Bitmap, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Ink)) {
        Image(
            bitmap = photo.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(22.dp).alpha(0.32f),
        )

        val transition = rememberInfiniteTransition(label = "breath")
        val breath by transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(1100, easing = LinearEasing), RepeatMode.Reverse
            ),
            label = "breathAlpha",
        )

        var hintIndex by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(2000)
                hintIndex = (hintIndex + 1) % HINTS.size
            }
        }

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                "诗",
                fontFamily = Kai,
                fontSize = 64.sp,
                color = Cinnabar,
                modifier = Modifier.alpha(breath),
            )
            AnimatedContent(
                targetState = hintIndex,
                transitionSpec = { fadeIn(tween(500)) togetherWith fadeOut(tween(500)) },
                label = "hint",
            ) { i ->
                Text(HINTS[i], fontFamily = Kai, fontSize = 15.sp, color = PaperDim)
            }
        }

        TextButton(
            onClick = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text("算了，重拍", color = Sand, fontFamily = Kai)
        }
    }
}
