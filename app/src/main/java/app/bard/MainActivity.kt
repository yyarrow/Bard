package app.bard

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.bard.ui.BardApp
import app.bard.ui.theme.BardTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // adb shell am start -n app.bard/.MainActivity --ez mock true → 不走 API，用一首固定诗调试排版
        val mock = intent?.getBooleanExtra("mock", false) ?: false
        setContent {
            BardTheme {
                BardApp(mockPoem = mock)
            }
        }
    }
}
