package app.bard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.bard.journal.Journal
import app.bard.ui.theme.Cinnabar
import app.bard.ui.theme.Ink
import app.bard.ui.theme.Kai
import app.bard.ui.theme.Paper
import app.bard.ui.theme.PaperDim
import app.bard.ui.theme.Sand
import app.bard.util.Almanac

/** 收集图鉴：从手账里长出来的收藏——诗人、朝代、节气。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = remember { Journal.list(context) }

    val authors = remember(entries) {
        entries.groupingBy { it.poem.author.ifBlank { "佚名" } }
            .eachCount().entries.sortedByDescending { it.value }
    }
    val dynasties = remember(entries) {
        entries.groupingBy { it.poem.dynasty.ifBlank { "—" } }
            .eachCount().entries.sortedByDescending { it.value }
    }
    val collectedTerms = remember(entries) {
        entries.map { Almanac.solarTerm(it.createdAt) }.toSet()
    }

    Column(
        Modifier.fillMaxSize().background(Ink).statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = Paper)
            }
            Text("图鉴", fontFamily = Kai, fontSize = 20.sp, color = Paper)
        }

        SectionTitle("集到的诗人", "${authors.size} 位")
        if (authors.isEmpty()) {
            EmptyHint()
        } else {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                authors.forEach { (name, n) ->
                    Chip("$name" + if (n > 1) " ×$n" else "", lit = true)
                }
            }
        }

        SectionTitle("朝代", "${dynasties.size} 个")
        if (dynasties.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                dynasties.forEach { (name, n) ->
                    Chip("$name" + if (n > 1) " ×$n" else "", lit = true)
                }
            }
        }

        SectionTitle("节气", "${collectedTerms.size} / 24")
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Almanac.termNames.forEach { term ->
                Chip(term, lit = term in collectedTerms)
            }
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun SectionTitle(title: String, count: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, fontFamily = Kai, fontSize = 16.sp, color = Paper)
        Text(
            count, fontFamily = Kai, fontSize = 12.sp, color = Sand,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

@Composable
private fun EmptyHint() {
    Text(
        "还空着，去拍照集诗吧",
        fontFamily = Kai, fontSize = 13.sp, color = PaperDim,
        modifier = Modifier.padding(horizontal = 18.dp),
    )
}

/** 点亮的芯片：收集到的用朱砂描边，未收集的暗着等你。 */
@Composable
private fun Chip(label: String, lit: Boolean) {
    Surface(
        color = if (lit) MaterialTheme.colorScheme.surfaceVariant
        else Ink.copy(alpha = 0.4f),
        shape = RoundedCornerShape(50),
        modifier = if (lit) {
            Modifier.border(1.dp, Cinnabar.copy(alpha = 0.55f), RoundedCornerShape(50))
        } else Modifier,
    ) {
        Text(
            label, fontFamily = Kai, fontSize = 13.sp,
            color = if (lit) Paper else PaperDim.copy(alpha = 0.45f),
            modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp),
            maxLines = 1,
        )
    }
}
