package dev.kura.scan.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Texte affichable : ressource traduite, ou message déjà en français venant de `:core` / du serveur. */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Raw(val text: String) : UiText

    @Composable
    fun resolve(): String = when (this) {
        is Res -> stringResource(id, *args.toTypedArray())
        is Raw -> text
    }
}

fun text(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

/** Bandeau de résultat (annoncé par TalkBack). */
data class Banner(val text: UiText, val kind: Kind = Kind.INFO, val id: Long = System.nanoTime()) {
    enum class Kind { INFO, SUCCESS, ERROR }
}

/** Valeur signée lisible : « +3 », « −1 » (vrai signe moins). */
fun signed(value: Long): String = if (value > 0) "+$value" else if (value < 0) "−${-value}" else "0"
