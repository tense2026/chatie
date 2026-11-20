package co.chatie.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import co.chatie.app.ui.ChatieNav
import co.chatie.app.ui.theme.ChatieTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as ChatieApp).container
        setContent {
            ChatieTheme {
                ChatieNav(container)
            }
        }
    }
}
