package com.ascan.ascanagent.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Scheme = darkColorScheme(
    primary = Purple,
    onPrimary = Color.White,
    secondary = PurpleDark,
    background = Bg,
    surface = Card,
    onBackground = Text,
    onSurface = Text,
    error = Red
)

@Composable
fun AScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Scheme,
        content = content
    )
}
