package dev.kura.core.scan

import dev.kura.core.config.TriggerConfig
import dev.kura.core.config.TriggerMode

/**
 * Déclenchement de la lecture caméra (ADR 0006), sans dépendance Android : le temps est fourni par l'appelant.
 *
 * - mode `button` : Idle → (appui / touche volume) → Armed(échéance) → Success | Timeout | Cancelled → Idle ;
 *   un nouvel appui pendant Armed annule ; la lecture s'arrête au premier code.
 * - mode `continuous` : toujours armé, chaque code lu est livré (le filtre « même code » est géré à part).
 */
class ScanTrigger(private val config: TriggerConfig) {

    enum class Source { BUTTON, VOLUME_KEY, CONTINUOUS }

    sealed interface State {
        data object Idle : State
        data class Armed(val deadlineMs: Long?, val source: Source) : State
        data class Success(val code: String) : State
        data object Timeout : State
        data object Cancelled : State
    }

    var state: State = if (config.mode == TriggerMode.CONTINUOUS) State.Armed(null, Source.CONTINUOUS) else State.Idle
        private set

    val isContinuous: Boolean get() = config.mode == TriggerMode.CONTINUOUS

    /** L'analyseur ne doit décoder que si ceci est vrai. */
    fun shouldDecode(nowMs: Long): Boolean {
        tick(nowMs)
        return state is State.Armed
    }

    /** Appui sur le bouton « Scanner ». */
    fun press(nowMs: Long): State = trigger(nowMs, Source.BUTTON)

    /** Touche de volume. Ignorée si `volume_keys` est désactivé ou en mode continu. Retourne `false` si ignorée. */
    fun volumeKey(nowMs: Long): Boolean {
        if (!config.volumeKeys || isContinuous) return false
        trigger(nowMs, Source.VOLUME_KEY)
        return true
    }

    private fun trigger(nowMs: Long, source: Source): State {
        if (isContinuous) return state
        tick(nowMs)
        state = when (state) {
            is State.Armed -> State.Cancelled
            else -> State.Armed(nowMs + config.timeoutMs, source)
        }
        return state
    }

    /** Code décodé par l'analyseur. Retourne le code accepté, ou `null` si la lecture n'était pas armée. */
    fun onDecoded(code: String, nowMs: Long): String? {
        tick(nowMs)
        if (state !is State.Armed) return null
        state = if (isContinuous) State.Armed(null, Source.CONTINUOUS) else State.Success(code)
        return code
    }

    /** Fait expirer une tentative. Retourne `true` si l'état vient de passer à Timeout. */
    fun tick(nowMs: Long): Boolean {
        val s = state
        if (s is State.Armed && s.deadlineMs != null && nowMs >= s.deadlineMs) {
            state = State.Timeout
            return true
        }
        return false
    }

    /** Retour au repos après affichage du résultat (Success / Timeout / Cancelled). */
    fun acknowledge() {
        if (state !is State.Armed) state = if (isContinuous) State.Armed(null, Source.CONTINUOUS) else State.Idle
    }

    /** Temps restant de la tentative en cours, pour l'affichage. */
    fun remainingMs(nowMs: Long): Long? = (state as? State.Armed)?.deadlineMs?.let { (it - nowMs).coerceAtLeast(0) }
}
