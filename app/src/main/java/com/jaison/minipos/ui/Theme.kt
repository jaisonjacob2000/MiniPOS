package com.jaison.minipos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1D3A5F),
    onPrimary = Color.White,
    secondary = Color(0xFFB4532A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC3F0),
    onPrimary = Color(0xFF0B1E33),
    secondary = Color(0xFFF0A27E),
)

@Composable
fun MiniPosTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
