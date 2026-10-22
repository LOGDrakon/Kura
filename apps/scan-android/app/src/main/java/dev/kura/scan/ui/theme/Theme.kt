package dev.kura.scan.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Palette Kura, sobre : sarcelle (action), ardoise (surfaces), vert/rouge réservés aux résultats.
private val Teal40 = Color(0xFF0F6E6E)
private val Teal80 = Color(0xFF7FD4D2)
private val Teal90 = Color(0xFFB4EEEC)
private val Slate10 = Color(0xFF101414)
private val Slate20 = Color(0xFF1C2222)
private val Slate95 = Color(0xFFF7F9F9)

private val Light = lightColorScheme(
    primary = Teal40,
    onPrimary = Color.White,
    primaryContainer = Teal90,
    onPrimaryContainer = Color(0xFF002020),
    secondary = Color(0xFF4A6363),
    background = Slate95,
    surface = Slate95,
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Teal90,
    secondary = Color(0xFFB1CCCB),
    background = Slate10,
    surface = Slate10,
    surfaceVariant = Slate20,
    error = Color(0xFFF2B8B5),
)

/** Couleurs de résultat (contraste vérifié sur fond clair et sombre). */
object KuraColors {
    val success = Color(0xFF1B7F3B)
    val successDark = Color(0xFF7BD88F)
}

@Composable
fun KuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) KuraColors.successDark else KuraColors.success
