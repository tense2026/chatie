package co.chatie.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import co.chatie.app.ui.ChatieNav
import co.chatie.app.ui.CrashScreen
import co.chatie.app.ui.theme.ChatieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as ChatieApp
        val container = app.container
        val startupError = app.startupError

        setContent {
            ChatieTheme {
                // A saved trace outranks the app: a crash that happens before
                // any UI appears would otherwise be unreadable on a phone.
                var pending by remember { mutableStateOf(startupError ?: CrashLog.read(this)) }
                val trace = pending

                if (trace != null) {
                    CrashScreen(
                        trace = trace,
                        canContinue = container != null,
                        onDismiss = {
                            CrashLog.clear(this)
                            pending = null
                        },
                    )
                } else if (container != null) {
                    ChatieNav(container)
                } else {
                    CrashScreen(
                        trace = "앱을 초기화하지 못했습니다. 원인을 기록하지 못했습니다.",
                        canContinue = false,
                        onDismiss = {},
                    )
                }
            }
        }
    }
}
