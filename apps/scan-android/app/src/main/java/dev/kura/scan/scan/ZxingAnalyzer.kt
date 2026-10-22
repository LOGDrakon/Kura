package dev.kura.scan.scan

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import dev.kura.core.scan.Symbology

fun Symbology.toZxing(): BarcodeFormat = when (this) {
    Symbology.QR_CODE -> BarcodeFormat.QR_CODE
    Symbology.DATA_MATRIX -> BarcodeFormat.DATA_MATRIX
    Symbology.EAN_13 -> BarcodeFormat.EAN_13
    Symbology.EAN_8 -> BarcodeFormat.EAN_8
    Symbology.UPC_A -> BarcodeFormat.UPC_A
    Symbology.UPC_E -> BarcodeFormat.UPC_E
    Symbology.CODE_128 -> BarcodeFormat.CODE_128
    Symbology.CODE_39 -> BarcodeFormat.CODE_39
    Symbology.ITF -> BarcodeFormat.ITF
}

/**
 * Analyseur CameraX → ZXing (ADR 0002 / 0006).
 * - ne décode que si [isArmed] (bouton « Scanner » appuyé, ou mode continu) ;
 * - recadre sur la zone de visée centrale ([aimArea] = part de la largeur affichée) avant ZXing ;
 * - remet l'image dans le sens de l'écran (les codes 1D horizontaux à l'écran restent horizontaux pour ZXing) ;
 * - réutilise ses tampons (pas d'allocation par image sur les téléphones modestes).
 * Appelé sur un exécuteur unique en arrière-plan.
 */
class ZxingAnalyzer(
    symbologies: Set<Symbology>,
    private val aimArea: () -> Float,
    private val isArmed: () -> Boolean,
    private val onCode: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to symbologies.map { it.toZxing() },
                // Coûteux, mais seulement sur la zone recadrée et seulement pendant une tentative.
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )
    }

    private var crop = ByteArray(0)
    private var rotated = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        try {
            if (!isArmed()) return
            decode(image)?.let(onCode)
        } catch (e: RuntimeException) {
            // Image inattendue (format, dimensions) : on ignore cette image, la suivante sera tentée.
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0] // luminance (Y)
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val width = image.width
        val height = image.height
        val rotation = image.imageInfo.rotationDegrees
        val swapped = rotation == 90 || rotation == 270

        // Zone de visée exprimée dans le repère de l'écran, puis ramenée dans celui du capteur.
        val shownW = if (swapped) height else width
        val shownH = if (swapped) width else height
        val boxW = (shownW * aimArea().coerceIn(0.2f, 1f)).toInt().coerceIn(32, shownW)
        val boxH = (boxW * AIM_RATIO).toInt().coerceIn(32, shownH)
        val cw = if (swapped) boxH else boxW
        val ch = if (swapped) boxW else boxH
        val left = (width - cw) / 2
        val top = (height - ch) / 2

        val size = cw * ch
        if (crop.size < size) crop = ByteArray(size)
        for (row in 0 until ch) {
            val offset = (top + row) * rowStride + left * pixelStride
            if (pixelStride == 1) {
                buffer.position(offset)
                buffer.get(crop, row * cw, cw)
            } else {
                for (col in 0 until cw) crop[row * cw + col] = buffer.get(offset + col * pixelStride)
            }
        }

        val (data, dw, dh) = rotate(crop, cw, ch, rotation)
        val source = PlanarYUVLuminanceSource(data, dw, dh, 0, 0, dw, dh, false)
        return try {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (e: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotate(src: ByteArray, w: Int, h: Int, rotation: Int): Triple<ByteArray, Int, Int> {
        if (rotation == 0) return Triple(src, w, h)
        val size = w * h
        if (rotated.size < size) rotated = ByteArray(size)
        val dst = rotated
        when (rotation) {
            90 -> for (y in 0 until h) for (x in 0 until w) dst[x * h + (h - 1 - y)] = src[y * w + x]
            180 -> for (i in 0 until size) dst[size - 1 - i] = src[i]
            270 -> for (y in 0 until h) for (x in 0 until w) dst[(w - 1 - x) * h + y] = src[y * w + x]
            else -> return Triple(src, w, h)
        }
        return if (rotation == 180) Triple(dst, w, h) else Triple(dst, h, w)
    }

    companion object {
        /** Hauteur de la zone de visée par rapport à sa largeur (repris par le cadre dessiné à l'écran). */
        const val AIM_RATIO = 0.75f
    }
}
