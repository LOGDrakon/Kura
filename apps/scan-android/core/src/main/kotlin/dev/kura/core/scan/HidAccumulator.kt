package dev.kura.core.scan

/**
 * Reconstitue les lectures d'un scanner Bluetooth/USB en mode clavier (HID) : une rafale de caractères
 * très rapprochés terminée par Entrée (ou Tab).
 *
 * Un humain qui tape est plus lent que [maxInterKeyMs] entre deux touches : sa saisie est ignorée,
 * pour ne pas confondre une frappe au clavier avec une lecture.
 */
class HidAccumulator(
    private val maxInterKeyMs: Long = 50,
    private val minLength: Int = 3,
    private val maxLength: Int = 512,
) {
    private val buffer = StringBuilder()
    private var lastKeyMs: Long = Long.MIN_VALUE
    private var humanPaced = false

    /** Caractère imprimable reçu. */
    fun onChar(c: Char, nowMs: Long) {
        if (buffer.isNotEmpty() && nowMs - lastKeyMs > maxInterKeyMs) {
            // Pause trop longue : ce qui précède n'était pas une lecture, on repart de ce caractère.
            buffer.setLength(0)
            humanPaced = false
        }
        if (buffer.length >= maxLength) {
            buffer.setLength(0)
            humanPaced = true
        }
        buffer.append(c)
        lastKeyMs = nowMs
    }

    /**
     * Touche de fin (Entrée / Tab). Retourne le code lu, ou `null` si la saisie ressemble à une frappe humaine
     * ou est trop courte.
     */
    fun onTerminator(nowMs: Long): String? {
        val tooSlow = buffer.isNotEmpty() && nowMs - lastKeyMs > maxInterKeyMs * 4
        val text = buffer.toString()
        val valid = !humanPaced && !tooSlow && text.length >= minLength
        reset()
        return if (valid) text else null
    }

    fun reset() {
        buffer.setLength(0)
        lastKeyMs = Long.MIN_VALUE
        humanPaced = false
    }

    val pending: Int get() = buffer.length
}
