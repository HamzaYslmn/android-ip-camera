package com.github.digitallyrefined.androidipcamera.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.view.Surface

/**
 * Raw Camera2 capture into a target Surface (the GL pipe's input). Works on all hardware levels
 * including LEGACY. Controls are generic: [extras] is a key->value map (CameraControls vocabulary)
 * applied at session config and live via [applyParam]. Only controls this device actually exposes
 * take effect — the rest of the map is ignored (the UI only offers the supported ones).
 */
class Camera2Capture(
    private val context: Context,
    private val cameraId: String,
    private val target: Surface,
    extras: Map<String, String> = emptyMap()
) {
    private val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val ch by lazy { cm.getCameraCharacteristics(cameraId) }
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var builder: CaptureRequest.Builder? = null
    @Volatile private var torchOn = false

    private val manualSensor: Boolean by lazy {
        ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
    }
    private val isoRange: Range<Int>? by lazy { ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
    private val expRange: Range<Long>? by lazy { ch.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }

    // mutable control state, seeded from extras
    private var evIndex = extras["exposure"]?.toIntOrNull() ?: 0
    private var fpsCap = extras["fps"]?.toIntOrNull() ?: 30
    private var iso = extras["iso"]?.toIntOrNull() ?: 0
    private var awbMode = extras["wb"]?.let { CameraControls.intOf(CameraControls.AWB, it) }
    private var effectMode = extras["effect"]?.let { CameraControls.intOf(CameraControls.EFFECT, it) }
    private var sceneMode = extras["scene"]?.let { CameraControls.intOf(CameraControls.SCENE, it) }
    private var afMode = extras["focusmode"]?.let { CameraControls.intOf(CameraControls.AF, it) }
    private var antiband = extras["antibanding"]?.let { CameraControls.intOf(CameraControls.ANTIBAND, it) }
    private var stab = extras["stabilization"] == "on"
    private var zoom = extras["zoom"]?.toFloatOrNull() ?: 1f

    @SuppressLint("MissingPermission")
    fun start() {
        thread = HandlerThread("cam2").apply { start() }
        handler = Handler(thread!!.looper)
        try {
            cm.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(c: CameraDevice) {
                    device = c
                    try {
                        val b = c.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                        b.addTarget(target)
                        builder = b
                        applyManual(b)
                        @Suppress("DEPRECATION")
                        c.createCaptureSession(listOf(target), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                try { s.setRepeatingRequest(b.build(), null, handler) } catch (e: Exception) { Log.e(TAG, "repeat: ${e.message}") }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) { Log.e(TAG, "session configure failed") }
                        }, handler)
                    } catch (e: Exception) { Log.e(TAG, "open->session: ${e.message}") }
                }
                override fun onDisconnected(c: CameraDevice) { c.close(); device = null }
                override fun onError(c: CameraDevice, e: Int) { Log.e(TAG, "device error $e"); c.close(); device = null }
            }, handler)
        } catch (e: Exception) { Log.e(TAG, "openCamera: ${e.message}") }
    }

    /** Push every manual setting onto [b]. Reused by start() and the live setters. */
    private fun applyManual(b: CaptureRequest.Builder) {
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex.coerceIn(it.lower, it.upper))
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode ?: CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode ?: CaptureRequest.CONTROL_AWB_MODE_AUTO)
        effectMode?.let { b.set(CaptureRequest.CONTROL_EFFECT_MODE, it) }
        antiband?.let { b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, it) }
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
            if (stab) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        cropFor(zoom)?.let { b.set(CaptureRequest.SCALER_CROP_REGION, it) }

        when {
            iso > 0 && manualSensor && isoRange != null -> {
                // Full manual exposure: ISO + exposure time (≈ 1/fps → low FPS = longer = brighter).
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(isoRange!!.lower, isoRange!!.upper))
                val expNs = 1_000_000_000L / fpsCap.coerceAtLeast(1)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expRange?.let { expNs.coerceIn(it.lower, it.upper) } ?: expNs)
            }
            sceneMode != null && sceneMode != 0 -> {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
                b.set(CaptureRequest.CONTROL_SCENE_MODE, sceneMode!!)
            }
            else -> {
                b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                pickFpsRange(fpsCap)?.let { b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
        }
        b.set(CaptureRequest.FLASH_MODE, if (torchOn) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
    }

    /** Centered crop rectangle for a digital-zoom [ratio] (1.0 = full sensor). */
    private fun cropFor(ratio: Float): Rect? {
        if (ratio <= 1f) return null
        val a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val w = (a.width() / ratio).toInt(); val h = (a.height() / ratio).toInt()
        val x = (a.width() - w) / 2; val y = (a.height() - h) / 2
        return Rect(x, y, x + w, y + h)
    }

    /** The advertised AE range whose upper is closest to (and preferably ≤) [target]; widest exposure window. */
    private fun pickFpsRange(target: Int): Range<Int>? {
        val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: return null
        return ranges.filter { it.upper <= target }.maxByOrNull { it.upper * 1000 - it.lower }
            ?: ranges.minByOrNull { it.upper }
    }

    private fun reapply() {
        val b = builder ?: return
        applyManual(b)
        try { session?.setRepeatingRequest(b.build(), null, handler) } catch (_: Exception) {}
    }

    fun applyParam(key: String, value: String) {
        when (key) {
            "exposure" -> evIndex = value.toIntOrNull() ?: evIndex
            "fps" -> fpsCap = (value.toIntOrNull() ?: fpsCap).coerceAtLeast(1)
            "iso" -> iso = (value.toIntOrNull() ?: 0).coerceAtLeast(0)
            "wb" -> awbMode = CameraControls.intOf(CameraControls.AWB, value)
            "effect" -> effectMode = CameraControls.intOf(CameraControls.EFFECT, value)
            "scene" -> sceneMode = CameraControls.intOf(CameraControls.SCENE, value)
            "focusmode" -> afMode = CameraControls.intOf(CameraControls.AF, value)
            "antibanding" -> antiband = CameraControls.intOf(CameraControls.ANTIBAND, value)
            "stabilization" -> stab = (value == "on" || value == "true")
            "zoom" -> zoom = value.toFloatOrNull() ?: zoom
            else -> return
        }
        reapply()
    }

    fun setTorch(on: Boolean) { torchOn = on; reapply() }

    fun triggerAutoFocus() {
        val s = session ?: return
        val b = builder ?: return
        try {
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL)
            s.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            s.capture(b.build(), null, handler)
            b.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE)
            b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
            s.setRepeatingRequest(b.build(), null, handler)
            Log.i(TAG, "AF+AE/AWB trigger sent")
        } catch (e: Exception) { Log.e(TAG, "AF: ${e.message}") }
    }

    fun stop() {
        try { session?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        session = null; device = null; builder = null
    }

    companion object { private const val TAG = "Camera2Capture" }
}
