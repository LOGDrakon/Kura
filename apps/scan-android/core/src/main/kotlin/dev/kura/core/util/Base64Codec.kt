package dev.kura.core.util

/**
 * Base64 (RFC 4648) standard et « url ». `java.util.Base64` n'existe qu'à partir d'Android 8 (API 26)
 * et n'est pas couvert par le désucrage : on garde donc une implémentation maison, minuscule et testée.
 */
object Base64Codec {
    private const val STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    fun encode(data: ByteArray, padding: Boolean = true): String = encode(data, STD, padding)

    fun encodeUrl(data: ByteArray, padding: Boolean = false): String = encode(data, URL, padding)

    /** Décode le base64 standard ; accepte l'absence de remplissage. Lève [IllegalArgumentException] si invalide. */
    fun decode(text: String): ByteArray = decode(text, STD)

    /** Décode le base64url ; accepte l'absence de remplissage. */
    fun decodeUrl(text: String): ByteArray = decode(text, URL)

    private fun encode(data: ByteArray, alphabet: String, padding: Boolean): String {
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = (data[i].u() shl 16) or (data[i + 1].u() shl 8) or data[i + 2].u()
            sb.append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63])
                .append(alphabet[(n ushr 6) and 63]).append(alphabet[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = data[i].u() shl 16
                sb.append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63])
                if (padding) sb.append("==")
            }
            2 -> {
                val n = (data[i].u() shl 16) or (data[i + 1].u() shl 8)
                sb.append(alphabet[n ushr 18]).append(alphabet[(n ushr 12) and 63]).append(alphabet[(n ushr 6) and 63])
                if (padding) sb.append('=')
            }
        }
        return sb.toString()
    }

    private fun decode(text: String, alphabet: String): ByteArray {
        val s = text.trimEnd('=')
        require(text.length - s.length <= 2) { "remplissage base64 invalide" }
        require(s.length % 4 != 1) { "longueur base64 invalide" }
        val out = ByteArray(s.length * 3 / 4)
        var buffer = 0
        var bits = 0
        var o = 0
        for (c in s) {
            val v = alphabet.indexOf(c)
            require(v >= 0) { "caractère base64 invalide : '$c'" }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[o++] = (buffer ushr bits).toByte()
            }
        }
        return out
    }

    private fun Byte.u(): Int = toInt() and 0xFF
}
