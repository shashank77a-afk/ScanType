package com.scantype.documentscanner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Scanner.ai brand palette: deep navy with blue, cyan, mint and violet accents.
val Blue = Color(0xFF2868E8)
val Cyan = Color(0xFF35D7C4)
val Navy = Color(0xFF10172D)
val Violet = Color(0xFF8057E8)
val Teal = Color(0xFF119A9A)
val Orange = Color(0xFFEE8A46)

/** mode: 0 = follow the system, 1 = light, 2 = dark. Existing Settings preferences are preserved. */
@Composable
fun ScanTypeTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF8EAFFF), onPrimary = Color(0xFF0A1A3A),
            primaryContainer = Color(0xFF244D9A), onPrimaryContainer = Color.White,
            secondary = Cyan, onSecondary = Color(0xFF052522), tertiary = Color(0xFFC0A6FF),
            background = Navy, surface = Color(0xFF171F38), surfaceVariant = Color(0xFF242D48),
            secondaryContainer = Color(0xFF174B50), outlineVariant = Color(0xFF38435F)
        )
    } else {
        lightColorScheme(
            primary = Blue, onPrimary = Color.White, secondary = Teal, tertiary = Violet,
            background = Color(0xFFF7F8FC), surface = Color.White,
            surfaceVariant = Color(0xFFEDF1FA), secondaryContainer = Color(0xFFDDF7F1),
            outlineVariant = Color(0xFFE0E5EF)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
