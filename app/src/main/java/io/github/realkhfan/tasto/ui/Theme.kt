package io.github.realkhfan.tasto.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

@Composable
fun TastoTheme(accent: Int, themePref: String, content: @Composable () -> Unit) {
    val dark = when (themePref) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val a = Color(accent)
    val scheme = if (dark) {
        darkColorScheme(
            primary = a,
            onPrimary = Color.White,
            primaryContainer = a.copy(alpha = 0.22f),
            onPrimaryContainer = Color.White,
            secondaryContainer = a.copy(alpha = 0.18f),
            onSecondaryContainer = Color.White,
            background = Color(0xFF111215),
            onBackground = Color(0xFFF2F3F5),
            surface = Color(0xFF111215),
            onSurface = Color(0xFFF2F3F5),
            surfaceVariant = Color(0xFF24262B),
            onSurfaceVariant = Color(0xFFA9AEB7),
            surfaceContainer = Color(0xFF1B1D21),
            surfaceContainerHigh = Color(0xFF24262B),
            surfaceContainerHighest = Color(0xFF2A2C31),
            outline = Color(0xFF3A3D44),
            outlineVariant = Color(0xFF2F3137),
            error = Color(0xFFFF6B6B),
        )
    } else {
        lightColorScheme(
            primary = a,
            onPrimary = Color.White,
            primaryContainer = a.copy(alpha = 0.14f),
            onPrimaryContainer = Color(0xFF15171B),
            secondaryContainer = a.copy(alpha = 0.14f),
            onSecondaryContainer = Color(0xFF15171B),
            background = Color(0xFFF4F5F7),
            onBackground = Color(0xFF15171B),
            surface = Color(0xFFF4F5F7),
            onSurface = Color(0xFF15171B),
            surfaceVariant = Color(0xFFE6E8EC),
            onSurfaceVariant = Color(0xFF5B616B),
            surfaceContainer = Color(0xFFFFFFFF),
            surfaceContainerHigh = Color(0xFFEDEFF2),
            surfaceContainerHighest = Color(0xFFE6E8EC),
            outline = Color(0xFFC8CCD3),
            outlineVariant = Color(0xFFDDE0E5),
            error = Color(0xFFD93636),
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
