package com.scantype.documentscanner

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Scanner.ai Viridis-inspired palette.
val ViridisGreen = Color(0xFF287D55)
val ViridisDeep = Color(0xFF173D2B)
val ViridisSoft = Color(0xFFDCEBE1)
val ViridisBackground = Color(0xFFF5F8F5)
val ViridisMuted = Color(0xFF527562)

// Compatibility aliases retained for existing screens; all map to the new brand palette.
val Blue = ViridisGreen
val Cyan = Color(0xFF12B99D)
val Navy = Color(0xFF101B16)
val Violet = Color(0xFF6F8F59)
val Teal = ViridisGreen
val Orange = Color(0xFFB8783E)

/** mode: 0 = follow the system, 1 = light, 2 = dark. Existing Settings preferences are preserved. */
@Composable
fun ScanTypeTheme(mode: Int, content: @Composable () -> Unit) {
    val dark = when (mode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val scheme = if (dark) {
        darkColorScheme(
            primary = Color(0xFF76D6A0), onPrimary = Color(0xFF092D1C),
            primaryContainer = Color(0xFF20593A), onPrimaryContainer = Color.White,
            secondary = Color(0xFF55D6B1), onSecondary = Color(0xFF082B21),
            tertiary = Color(0xFFB4D59A), background = Color(0xFF101B16),
            surface = Color(0xFF17251D), surfaceVariant = Color(0xFF24372B),
            secondaryContainer = Color(0xFF174B38), outlineVariant = Color(0xFF405749)
        )
    } else {
        lightColorScheme(
            primary = ViridisGreen, onPrimary = Color.White,
            secondary = Color(0xFF12A88C), onSecondary = Color.White,
            tertiary = Color(0xFF608B44), background = ViridisBackground,
            surface = Color.White, surfaceVariant = Color(0xFFE8EFEA),
            secondaryContainer = ViridisSoft, outlineVariant = Color(0xFFD8E2DA)
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
