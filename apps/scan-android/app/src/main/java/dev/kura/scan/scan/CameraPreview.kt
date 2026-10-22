package dev.kura.scan.scan

import android.annotation.SuppressLint
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors

/** Ce que l'appareil photo sait faire, pour adapter l'interface (lampe, mise au point). */
data class CameraCapabilities(val hasTorch: Boolean, val available: Boolean)

/**
 * Aperçu caméra (sert à viser) + analyse ZXing.
 * Robustesse entrée de gamme : 1280×720 visé avec repli vers une résolution inférieure, caméra arrière sinon avant,
 * lampe et mise au point au toucher seulement si l'appareil les gère.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    torchOn: Boolean,
    onCapabilities: (CameraCapabilities) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCapabilities by rememberUpdatedState(onCapabilities)
    var camera by remember { mutableStateOf<Camera?>(null) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            // COMPATIBLE (TextureView) : plus fiable sur les vieux GPU et dans une hiérarchie Compose.
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner, analyzer) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val p = future.get().also { provider = it }
                val selector = when {
                    p.hasCameraSafe(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    p.hasCameraSafe(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> null
                }
                if (selector == null) {
                    currentOnCapabilities(CameraCapabilities(hasTorch = false, available = false))
                    return@addListener
                }
                val resolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build()
                val preview = Preview.Builder().setResolutionSelector(resolution).build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(resolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, analyzer)
                p.unbindAll()
                val cam = p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                camera = cam
                currentOnCapabilities(CameraCapabilities(hasTorch = cam.cameraInfo.hasFlashUnit(), available = true))
            } catch (e: Exception) {
                // Caméra occupée, absente ou refusée par le pilote.
                camera = null
                currentOnCapabilities(CameraCapabilities(hasTorch = false, available = false))
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            try {
                provider?.unbindAll()
            } catch (e: Exception) {
                // déjà libérée
            }
            camera = null
        }
    }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    LaunchedEffect(camera, torchOn) {
        val cam = camera ?: return@LaunchedEffect
        if (cam.cameraInfo.hasFlashUnit()) {
            try {
                cam.cameraControl.enableTorch(torchOn)
            } catch (e: Exception) {
                // ignoré
            }
        }
    }

    AndroidView(
        factory = {
            previewView.apply {
                // Mise au point au toucher, seulement si l'optique le permet (pas d'autofocus = rien à faire).
                setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val cam = camera
                        if (cam != null) {
                            try {
                                val point = meteringPointFactory.createPoint(event.x, event.y)
                                val action = FocusMeteringAction.Builder(point).build()
                                if (cam.cameraInfo.isFocusMeteringSupported(action)) {
                                    cam.cameraControl.startFocusAndMetering(action)
                                }
                            } catch (e: Exception) {
                                // appareil sans mise au point pilotable
                            }
                        }
                        view.performClick()
                    }
                    true
                }
            }
        },
        modifier = modifier,
    )
}

private fun ProcessCameraProvider.hasCameraSafe(selector: CameraSelector): Boolean = try {
    hasCamera(selector)
} catch (e: Exception) {
    false
}
