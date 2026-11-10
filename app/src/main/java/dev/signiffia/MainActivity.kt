package dev.signiffia

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.signiffia.ui.AppRoot
import dev.signiffia.ui.theme.SigniffiaTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            SigniffiaTheme {
                AppRoot()
            }
        }
    }
}
