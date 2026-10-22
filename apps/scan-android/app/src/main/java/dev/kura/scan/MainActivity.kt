package dev.kura.scan

import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import dev.kura.scan.ui.KuraRoot
import dev.kura.scan.ui.scan.ScanViewModel
import dev.kura.scan.ui.theme.KuraTheme

/**
 * Activité unique. Route les touches de volume (déclenchement, ADR 0006) et les frappes d'un scanner
 * Bluetooth/USB ou d'une gâchette intégrée (HID) vers l'écran Scan.
 */
class MainActivity : ComponentActivity() {
    private val scanVm: ScanViewModel by viewModels()
    private var volumeConsumed = false

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KuraTheme {
                KuraRoot(windowSize = calculateWindowSizeClass(this), scanVm = scanVm)
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val time = event.eventTime.takeIf { it > 0 } ?: SystemClock.uptimeMillis()
            when (event.keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB -> {
                    if (scanVm.onHidTerminator(time)) return true
                }
                else -> {
                    val c = event.unicodeChar
                    if (c != 0 && !Character.isISOControl(c)) scanVm.onHidChar(c.toChar(), time)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.repeatCount == 0) volumeConsumed = scanVm.onVolumeKey()
            if (volumeConsumed) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if ((keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && volumeConsumed) {
            volumeConsumed = false
            return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
