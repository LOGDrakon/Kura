package dev.kura.core

import dev.kura.core.ops.OperationFactory
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

object TestSupport {
    val repoRoot: File get() = File(System.getProperty("kura.repoRoot") ?: "../../..")

    fun specFile(name: String): File = File(repoRoot, "spec/$name")

    fun fixedClock(iso: String = "2026-10-22T13:55:12Z"): Clock = Clock.fixed(Instant.parse(iso), ZoneOffset.UTC)

    /** Fabrique déterministe : op_id séquentiels (UUID v4 valides). */
    fun factory(clock: Clock = fixedClock(), device: String? = "tablette-atelier-2", user: String? = "jdupont"): OperationFactory {
        var n = 0
        return OperationFactory({ device }, { user }, clock) {
            n++
            "00000000-0000-4000-8000-" + n.toString().padStart(12, '0')
        }
    }
}
