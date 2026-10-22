package dev.kura.core.scan

/**
 * Ignore un même code relu pendant [cooldownMs] (ADR 0004 §4). D'après l'ADR 0006, ne s'applique
 * qu'au mode `continuous` et aux scanners HID : en mode bouton, chaque appui est une lecture voulue.
 */
class SameCodeFilter(private val cooldownMs: Long) {
    private var lastCode: String? = null
    private var lastAtMs: Long = Long.MIN_VALUE

    /** `true` si le code doit être pris en compte. */
    fun accept(code: String, nowMs: Long): Boolean {
        val duplicate = code == lastCode && cooldownMs > 0 && nowMs - lastAtMs < cooldownMs
        if (duplicate) return false
        lastCode = code
        lastAtMs = nowMs
        return true
    }

    fun reset() {
        lastCode = null
        lastAtMs = Long.MIN_VALUE
    }
}
