package com.github.digitallyrefined.androidipcamera.helpers

import android.graphics.SurfaceTexture
import android.hardware.Camera
import android.util.Log
import kotlin.math.abs

/**
 * Camera1 capture into a SurfaceTexture (the GL pipe). Opens and picks its real size up front so the
 * caller can build the encoder/pipe at exactly [chosenW]x[chosenH] — no size guessing, no stretch.
 *
 * All controls are generic: [extras] is a key->value map (keys from CameraControls vocabulary —
 * exposure/fps/iso/wb/effect/scene/focusmode/antibanding/stabilization/zoom) applied at start and
 * live via [applyParam]. Camera1 exposes the richest set on legacy hardware.
 */
@Suppress("DEPRECATION")
class Camera1Capture(
    cameraId: Int,
    targetW: Int,
    targetH: Int,
    private val extras: Map<String, String> = emptyMap()
) {
    // Retry: the previous session's release can lag, so a fresh open occasionally throws "in use".
    private val camera: Camera = run {
        var last: Exception? = null
        repeat(4) {
            try { return@run Camera.open(cameraId) } catch (e: Exception) { last = e; Thread.sleep(200) }
        }
        throw last ?: RuntimeException("Camera.open failed")
    }
    var chosenW = targetW; private set
    var chosenH = targetH; private set

    init {
        camera.parameters.supportedPreviewSizes?.let { sizes ->
            val pick = sizes.firstOrNull { it.width == targetW && it.height == targetH }
                ?: sizes.minByOrNull { abs(it.width * it.height - targetW * targetH) }
            pick?.let { chosenW = it.width; chosenH = it.height }
        }
    }

    /** This camera's supported controls as JSON (read live from the open params). */
    fun controlsJson(cur: (String) -> String?): String = CameraControls.camera1Json(camera.parameters, cur)

    fun start(st: SurfaceTexture) {
        val p = camera.parameters
        p.setPreviewSize(chosenW, chosenH)
        // sensible defaults, then overlay the user's extras
        applyFps(p, extras["fps"]?.toIntOrNull() ?: 30)
        p.focusMode = listOf(
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO,
            Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE,
            Camera.Parameters.FOCUS_MODE_AUTO
        ).firstOrNull { p.supportedFocusModes?.contains(it) == true } ?: p.focusMode
        if (p.supportedWhiteBalance?.contains(Camera.Parameters.WHITE_BALANCE_AUTO) == true)
            p.whiteBalance = Camera.Parameters.WHITE_BALANCE_AUTO
        extras.forEach { (k, v) -> setParam(p, k, v) }
        camera.parameters = p
        camera.setPreviewTexture(st)
        camera.startPreview()
        if (p.focusMode == Camera.Parameters.FOCUS_MODE_AUTO) try { camera.autoFocus(null) } catch (_: Exception) {}
        Log.i(TAG, "Camera1 preview ${chosenW}x$chosenH ${extras}")
    }

    /** Apply one control live (setParameters while previewing). */
    fun applyParam(key: String, value: String) {
        try { val p = camera.parameters; setParam(p, key, value); camera.parameters = p } catch (_: Exception) {}
    }

    private fun setParam(p: Camera.Parameters, key: String, value: String) {
        try {
            when (key) {
                "exposure" -> { val lo = p.minExposureCompensation; val hi = p.maxExposureCompensation
                    if (lo != hi) p.exposureCompensation = (value.toIntOrNull() ?: 0).coerceIn(lo, hi) }
                "fps" -> applyFps(p, value.toIntOrNull() ?: 30)
                "iso" -> { val v = if (value == "0" || value.equals("auto", true)) "auto" else value
                    val key2 = if (p.get("iso-values") != null || p.get("iso") != null) "iso" else "iso-speed"
                    p.set(key2, v) }
                "wb" -> if (p.supportedWhiteBalance?.contains(value) == true) p.whiteBalance = value
                "effect" -> if (p.supportedColorEffects?.contains(value) == true) p.colorEffect = value
                "scene" -> if (p.supportedSceneModes?.contains(value) == true) p.sceneMode = value
                "focusmode" -> if (p.supportedFocusModes?.contains(value) == true) p.focusMode = value
                "antibanding" -> if (p.supportedAntibanding?.contains(value) == true) p.antibanding = value
                "stabilization" -> if (p.isVideoStabilizationSupported) p.videoStabilization = (value == "on" || value == "true")
                "zoom" -> if (p.isZoomSupported) {
                    val want = ((value.toFloatOrNull() ?: 1f) * 100).toInt()
                    val ratios = p.zoomRatios
                    if (ratios != null) p.zoom = ratios.indices.minByOrNull { abs(ratios[it] - want) } ?: 0
                }
            }
        } catch (_: Exception) {}
    }

    private fun applyFps(p: Camera.Parameters, cap: Int) {
        // Pick the advertised range whose max is closest to (and preferably ≤) the cap → widest exposure window.
        p.supportedPreviewFpsRange?.let { ranges ->
            val want = cap.coerceAtLeast(1) * 1000
            val r = ranges.filter { it[1] <= want }.maxByOrNull { it[1] } ?: ranges.minByOrNull { it[1] }
            r?.let { p.setPreviewFpsRange(it[0], it[1]) }
        }
    }

    fun setTorch(on: Boolean) {
        try {
            val p = camera.parameters
            if (p.supportedFlashModes?.contains(Camera.Parameters.FLASH_MODE_TORCH) == true) {
                p.flashMode = if (on) Camera.Parameters.FLASH_MODE_TORCH else Camera.Parameters.FLASH_MODE_OFF
                camera.parameters = p
            }
        } catch (_: Exception) {}
    }

    fun triggerAutoFocus() {
        try {
            val p = camera.parameters
            if (p.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_AUTO) == true) {
                p.focusMode = Camera.Parameters.FOCUS_MODE_AUTO  // continuous ignores autoFocus(); AUTO scans
                camera.parameters = p
            }
            camera.cancelAutoFocus()
            camera.autoFocus { ok, c ->
                Log.i(TAG, "AF result=$ok")
                try {
                    val pp = c.parameters
                    if (pp.supportedFocusModes?.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO) == true) {
                        pp.focusMode = Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO
                        c.parameters = pp
                    }
                } catch (_: Exception) {}
            }
        } catch (e: Exception) { Log.e(TAG, "AF: ${e.message}") }
    }

    fun stop() {
        try { camera.stopPreview() } catch (_: Exception) {}
        try { camera.release() } catch (_: Exception) {}
    }

    companion object { private const val TAG = "Camera1Capture" }
}
