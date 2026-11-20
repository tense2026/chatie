package co.chatie.app.ui

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import co.chatie.app.ui.theme.CodeTextStyle
import kotlinx.coroutines.launch

/**
 * Shown instead of the app when start-up failed or the previous run crashed.
 *
 * The trace is selectable and copyable because the person running this has no
 * logcat: this screen is the bug report.
 */
@Composable
fun CrashScreen(
    trace: String,
    onDismiss: () -> Unit,
    canContinue: Boolean,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("앱이 중단되었습니다", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "아래 내용을 복사해서 전달해 주세요. 원인이 그대로 들어 있습니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    scope.launch {
                        clipboard.setClipEntry(
                            ClipEntry(ClipData.newPlainText("chatie crash", trace)),
                        )
                    }
                },
            ) { Text("복사") }
            OutlinedButton(onClick = onDismiss) {
                Text(if (canContinue) "지우고 계속" else "지우기")
            }
        }

        SelectionContainer(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = trace, style = CodeTextStyle)
        }
    }
}
