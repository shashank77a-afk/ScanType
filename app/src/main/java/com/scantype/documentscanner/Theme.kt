package com.scantype.documentscanner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Blue = Color(0xFF0A5BFF)
val Cyan = Color(0xFF00C2FF)
val Navy = Color(0xFF0A1633)

@Composable
fun ScanTypeTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Cyan, onPrimary = Navy, background = Navy, surface = Color(0xFF11214A),
            surfaceVariant = Color(0xFF14264D), secondaryContainer = Color(0xFF14264D))
    else lightColorScheme(primary = Blue, secondary = Cyan, background = Color(0xFFF6F9FF),
        surface = Color.White, surfaceVariant = Color(0xFFE8F0FF), secondaryContainer = Color(0xFFD6E6FF))
    MaterialTheme(colorScheme = scheme, content = content)
}
