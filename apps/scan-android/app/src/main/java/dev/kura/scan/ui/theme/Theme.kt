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
    secondaryContainer = Color(0xFFDCE8E7),
    onSecondaryContainer = Color(0xFF152020),
    background = Slate95,
    surface = Slate95,
    surfaceVariant = Color(0xFFE3EAE9),
    onSurfaceVariant = Color(0xFF3F4948),
    surfaceContainerLow = Color(0xFFF0F4F4),
    surfaceContainer = Color(0xFFEAF0EF),
    surfaceContainerHigh = Color(0xFFE4EBEA),
    surfaceContainerHighest = Color(0xFFDEE5E4),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFF6F7979),
    outlineVariant = Color(0xFFBEC9C8),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Teal80,
    onPrimary = Color(0xFF003737),
    primaryContainer = Color(0xFF004F4F),
    onPrimaryContainer = Teal90,
    secondary = Color(0xFFB1CCCB),
    secondaryContainer = Color(0xFF2E3B3B),
    onSecondaryContainer = Color(0xFFDCE8E7),
    background = Slate10,
    surface = Slate10,
    surfaceVariant = Slate20,
    onSurfaceVariant = Color(0xFFBEC9C8),
    surfaceContainerLow = Color(0xFF161B1B),
    surfaceContainer = Slate20,
    surfaceContainerHigh = Color(0xFF262D2D),
    surfaceContainerHighest = Color(0xFF303838),
    surfaceContainerLowest = Color(0xFF0B0F0F),
    outline = Color(0xFF889392),
    outlineVariant = Color(0xFF3F4948),
    error = Color(0xFFF2B8B5),
)

/** Couleurs de résultat et de sens du mouvement (contraste vérifié sur fond clair et sombre). */
object KuraColors {
    val success = Color(0xFF1B7F3B)
    val successDark = Color(0xFF7BD88F)
    val outgoing = Color(0xFFB54708)
    val outgoingDark = Color(0xFFFFB27A)
}

@Composable
fun KuraTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Couleurs Kura fixes : l'appli garde la même allure sur tous les appareils (pas de couleurs du fond d'écran).
    dynamicColor: Boolean = false,
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

/** Entrée de stock (vert) ou sortie (orange) ; neutre si nul. */
@Composable
fun deltaColor(delta: Long): Color = when {
    delta > 0 -> successColor()
    delta < 0 -> if (isSystemInDarkTheme()) KuraColors.outgoingDark else KuraColors.outgoing
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
