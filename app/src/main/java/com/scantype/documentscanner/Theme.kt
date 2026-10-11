package com.scantype.documentscanner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colours. The names come from the first version; the values are now the green theme.
val Blue = Color(0xFF0A7F4F)   // primary green
val Cyan = Color(0xFF3DDC97)   // mint accent
val Navy = Color(0xFF07261A)   // deep green-black

/** mode: 0 = follow the system, 1 = light, 2 = dark. */
@Composable
fun ScanTypeTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark)
        darkColorScheme(primary = Cyan, onPrimary = Navy, primaryContainer = Color(0xFF0F5C3C), onPrimaryContainer = Color.White,
            background = Navy, surface = Color(0xFF0F3324), surfaceVariant = Color(0xFF164532), secondaryContainer = Color(0xFF164532))
    else lightColorScheme(primary = Blue, secondary = Cyan, background = Color(0xFFF3FAF6), surface = Color.White,
        surfaceVariant = Color(0xFFE0F2E9), secondaryContainer = Color(0xFFCDEBDD))
    MaterialTheme(colorScheme = scheme, content = content)
}
