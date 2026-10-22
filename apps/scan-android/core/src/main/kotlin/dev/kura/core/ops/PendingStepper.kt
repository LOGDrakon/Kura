package dev.kura.core.ops

/**
 * Saisie de la fiche avant « Valider » : les appuis +/− s'accumulent en **un seul** delta
 * (architecture §4.3) ; une quantité saisie bascule en correction (`set`).
 *
 * Immuable : chaque action renvoie un nouvel état.
 */
data class PendingStepper(
    /** Quantité connue (serveur ou cache), `null` si inconnue. */
    val baseQuantity: Long?,
    /** Somme des appuis +/− (mode delta). */
    val pendingDelta: Long = 0,
    /** Quantité cible saisie (mode set), `null` en mode delta. */
    val setTarget: Long? = null,
    val allowSet: Boolean = true,
) {
    val isSetMode: Boolean get() = setTarget != null
    val hasChanges: Boolean get() = if (isSetMode) setTarget != baseQuantity || baseQuantity == null else pendingDelta != 0L

    /** Quantité affichée après validation (si calculable). */
    val projectedQuantity: Long?
        get() = setTarget ?: baseQuantity?.plus(pendingDelta)

    /** Appui sur un bouton de pas (positif = entrée, négatif = sortie). */
    fun step(amount: Long): PendingStepper =
        if (setTarget != null) copy(setTarget = (setTarget + amount).coerceAtLeast(0))
        else copy(pendingDelta = pendingDelta + amount)

    /** Saisie directe de la quantité (correction d'inventaire). Sans effet si `allow_set` est faux. */
    fun setQuantity(value: Long): PendingStepper {
        require(value >= 0) { "quantité négative" }
        if (!allowSet) return this
        return copy(setTarget = value, pendingDelta = 0)
    }

    /** Retour au mode delta sans changement. */
    fun reset(newBase: Long? = baseQuantity): PendingStepper = PendingStepper(newBase, allowSet = allowSet)

    /** Opération à créer à la validation, ou `null` s'il n'y a rien à envoyer. */
    fun toOperation(itemId: String, factory: OperationFactory, sessionId: String? = null): Operation? = when {
        setTarget != null -> if (hasChanges) factory.set(itemId, setTarget, sessionId) else null
        pendingDelta != 0L -> factory.delta(itemId, pendingDelta, sessionId)
        else -> null
    }
}
