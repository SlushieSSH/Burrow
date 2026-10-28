package com.bigslushie.burrow

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color as ComposeColor

private val black = ComposeColor(0xFF000000)
private val white = ComposeColor(0xFFFFFFFF)
private val gray = ComposeColor(0xFF9E9E9E)
private val darkGray = ComposeColor(0xFF1C1C1C)
private val outlineGray = ComposeColor(0xFF3A3A3A)
private val panelGray = ComposeColor(0xFF141414)
private val raisedGray = ComposeColor(0xFF2A2A2A)

private val burrowColors = darkColorScheme(
    primary = white,
    onPrimary = black,
    secondary = gray,
    onSecondary = black,
    background = black,
    onBackground = white,
    surface = black,
    onSurface = white,
    surfaceVariant = darkGray,
    onSurfaceVariant = gray,
    surfaceContainerLow = black,
    surfaceContainer = panelGray,
    surfaceContainerHigh = darkGray,
    surfaceContainerHighest = raisedGray,
    outline = outlineGray,
    outlineVariant = darkGray,
    error = white,
    onError = black,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.BLACK),
            navigationBarStyle = SystemBarStyle.dark(Color.BLACK),
        )
        setContent {
            MaterialTheme(colorScheme = burrowColors) {
                StorageGate { AppScreen() }
            }
        }
    }
}
