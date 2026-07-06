package app.bard.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import app.bard.R

// 墨、纸、朱砂 —— 整个 app 的用色都从一幅字画里来
val Ink = Color(0xFF17140F)
val InkSurface = Color(0xFF221E17)
val Paper = Color(0xFFF5EEDC)
val PaperDim = Color(0xFFD8CDB4)
val Cinnabar = Color(0xFFB3352C)
val Sand = Color(0xFFB7A98D)

val Kai = FontFamily(Font(R.font.lxgw))

private val Scheme = darkColorScheme(
    primary = Cinnabar,
    onPrimary = Color(0xFFFFF6EA),
    background = Ink,
    onBackground = Paper,
    surface = InkSurface,
    onSurface = Paper,
    secondary = Sand,
    onSecondary = Ink,
    surfaceVariant = Color(0xFF2B261E),
    onSurfaceVariant = PaperDim,
)

@Composable
fun BardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        content = content,
    )
}
