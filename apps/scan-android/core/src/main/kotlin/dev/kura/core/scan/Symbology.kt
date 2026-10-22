package dev.kura.core.scan

/** Formats de code acceptés (ADR 0002). [schemaName] = nom dans `scan.symbologies`. L'appli les traduit en `BarcodeFormat` ZXing. */
enum class Symbology(val schemaName: String) {
    QR_CODE("qr_code"),
    DATA_MATRIX("data_matrix"),
    EAN_13("ean_13"),
    EAN_8("ean_8"),
    UPC_A("upc_a"),
    UPC_E("upc_e"),
    CODE_128("code_128"),
    CODE_39("code_39"),
    ITF("itf");

    val is2D: Boolean get() = this == QR_CODE || this == DATA_MATRIX

    companion object {
        val DEFAULT = listOf(QR_CODE, EAN_13, EAN_8, CODE_128)

        fun fromSchemaName(name: String): Symbology? = entries.firstOrNull { it.schemaName == name }

        /** Noms inconnus ignorés (la config est validée en amont) ; liste vide → valeurs par défaut. */
        fun fromSchemaNames(names: List<String>): Set<Symbology> =
            names.mapNotNull { fromSchemaName(it) }.toSet().ifEmpty { DEFAULT.toSet() }
    }
}
