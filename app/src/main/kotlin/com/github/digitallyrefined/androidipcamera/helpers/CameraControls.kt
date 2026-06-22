package com.github.digitallyrefined.androidipcamera.helpers

import android.hardware.Camera
import android.hardware.camera2.CameraCharacteristics

/**
 * One vocabulary of camera controls, shared by every backend, so the UI can show — per API —
 * exactly what that API supports on the current camera. Each backend enumerates its OWN
 * capabilities (Camera2 from CameraCharacteristics, Camera1 from Camera.Parameters, CameraX a
 * fixed minimal set). Control names are canonical strings; Camera2's int enums are mapped to the
 * same names so the UI vocabulary is identical across APIs.
 *
 * JSON per control: {"key","label","type":"enum"|"range", value, (values[] | min,max,step)}
 */
object CameraControls {
    // canonical name -> Camera2 int constant
    val AWB = linkedMapOf("auto" to 1, "incandescent" to 2, "fluorescent" to 3,
        "warm-fluorescent" to 4, "daylight" to 5, "cloudy-daylight" to 6,
        "twilight" to 7, "shade" to 8, "off" to 0)
    val EFFECT = linkedMapOf("none" to 0, "mono" to 1, "negative" to 2, "solarize" to 3,
        "sepia" to 4, "posterize" to 5, "whiteboard" to 6, "blackboard" to 7, "aqua" to 8)
    val SCENE = linkedMapOf("auto" to 0, "face-priority" to 1, "action" to 2, "portrait" to 3,
        "landscape" to 4, "night" to 5, "night-portrait" to 6, "theatre" to 7, "beach" to 8,
        "snow" to 9, "sunset" to 10, "steadyphoto" to 11, "fireworks" to 12, "sports" to 13,
        "party" to 14, "candlelight" to 15, "barcode" to 16, "hdr" to 18)
    val AF = linkedMapOf("fixed" to 0, "auto" to 1, "macro" to 2,
        "continuous-video" to 3, "continuous-picture" to 4, "edof" to 5)
    val ANTIBAND = linkedMapOf("off" to 0, "50hz" to 1, "60hz" to 2, "auto" to 3)

    fun nameOf(map: Map<String, Int>, i: Int): String? = map.entries.firstOrNull { it.value == i }?.key
    fun intOf(map: Map<String, Int>, name: String): Int? = map[name]

    private fun en(key: String, label: String, values: List<String>, value: String) =
        "{\"key\":\"$key\",\"label\":\"$label\",\"type\":\"enum\",\"value\":\"$value\"," +
        "\"values\":[${values.joinToString(",") { "\"$it\"" }}]}"
    private fun rg(key: String, label: String, min: Double, max: Double, step: Double, value: String) =
        "{\"key\":\"$key\",\"label\":\"$label\",\"type\":\"range\"," +
        "\"min\":$min,\"max\":$max,\"step\":$step,\"value\":\"$value\"}"

    private fun arr(items: List<String>) = "[${items.joinToString(",")}]"

    // ---------- Camera2 (from characteristics; no open required, works while streaming) ----------
    fun camera2Json(ch: CameraCharacteristics, cur: (String) -> String?): String {
        val out = ArrayList<String>()
        ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)?.let { r ->
            val step = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                ?.let { it.numerator.toDouble() / it.denominator } ?: 1.0
            out += rg("exposure", "Exposure (EV)", r.lower.toDouble(), r.upper.toDouble(), step, cur("exposure") ?: "0")
        }
        val fpsMax = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.maxOfOrNull { it.upper } ?: 30
        out += rg("fps", "FPS cap (lower = brighter)", 5.0, fpsMax.toDouble(), 1.0, cur("fps") ?: "$fpsMax")
        val manual = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        ch.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let {
            if (manual) out += rg("iso", "ISO", it.lower.toDouble(), it.upper.toDouble(), 50.0, cur("iso") ?: "0")
        }
        enumInts(ch.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES), AWB)?.let { out += en("wb", "White balance", it, cur("wb") ?: it.first()) }
        enumInts(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS), EFFECT)?.let { out += en("effect", "Color effect", it, cur("effect") ?: it.first()) }
        enumInts(ch.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES), SCENE)?.let { out += en("scene", "Scene mode", it, cur("scene") ?: it.first()) }
        enumInts(ch.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES), AF)?.let { out += en("focusmode", "Focus mode", it, cur("focusmode") ?: it.first()) }
        enumInts(ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES), ANTIBAND)?.let { out += en("antibanding", "Antibanding", it, cur("antibanding") ?: it.first()) }
        ch.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.let {
            if (it.contains(1)) out += en("stabilization", "Stabilization", listOf("off", "on"), cur("stabilization") ?: "off")
        }
        ch.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.let {
            if (it > 1f) out += rg("zoom", "Zoom", 1.0, it.toDouble(), 0.1, cur("zoom") ?: "1.0")
        }
        return arr(out)
    }

    private fun enumInts(arr: IntArray?, map: Map<String, Int>): List<String>? {
        val v = arr?.toList()?.mapNotNull { nameOf(map, it) }?.distinct() ?: return null
        return if (v.size > 1) v else null
    }

    // ---------- Camera1 (from open Camera.Parameters; native string enums) ----------
    @Suppress("DEPRECATION")
    fun camera1Json(p: Camera.Parameters, cur: (String) -> String?): String {
        val out = ArrayList<String>()
        if (p.minExposureCompensation != p.maxExposureCompensation)
            out += rg("exposure", "Exposure (EV)", p.minExposureCompensation.toDouble(),
                p.maxExposureCompensation.toDouble(), 1.0, cur("exposure") ?: "0")
        val fpsMax = (p.supportedPreviewFpsRange?.maxOfOrNull { it[1] } ?: 30000) / 1000
        out += rg("fps", "FPS cap (lower = brighter)", 5.0, fpsMax.toDouble(), 1.0, cur("fps") ?: "$fpsMax")
        // ISO is a Qualcomm vendor enum on Camera1 (values like auto, ISO100..ISO1600)
        (p.get("iso-values") ?: p.get("iso-speed-values"))?.split(",")?.map { it.trim() }
            ?.filter { it.isNotBlank() }?.let { if (it.size > 1) out += en("iso", "ISO", it, cur("iso") ?: it.first()) }
        enumStrs(p.supportedWhiteBalance)?.let { out += en("wb", "White balance", it, cur("wb") ?: it.first()) }
        enumStrs(p.supportedColorEffects)?.let { out += en("effect", "Color effect", it, cur("effect") ?: it.first()) }
        enumStrs(p.supportedSceneModes)?.let { out += en("scene", "Scene mode", it, cur("scene") ?: it.first()) }
        enumStrs(p.supportedFocusModes)?.let { out += en("focusmode", "Focus mode", it, cur("focusmode") ?: it.first()) }
        enumStrs(p.supportedAntibanding)?.let { out += en("antibanding", "Antibanding", it, cur("antibanding") ?: it.first()) }
        if (p.isVideoStabilizationSupported)
            out += en("stabilization", "Stabilization", listOf("off", "on"), cur("stabilization") ?: "off")
        if (p.isZoomSupported && p.maxZoom > 0) {
            val maxR = (p.zoomRatios?.lastOrNull() ?: 100) / 100.0
            out += rg("zoom", "Zoom", 1.0, maxR, 0.1, cur("zoom") ?: "1.0")
        }
        return arr(out)
    }

    private fun enumStrs(v: List<String>?): List<String>? {
        val l = v?.distinct()?.filter { it.isNotBlank() } ?: return null
        return if (l.size > 1) l else null
    }

    // ---------- CameraX (fixed minimal set; CameraX exposes little) ----------
    fun cameraxJson(cur: (String) -> String?): String = arr(listOf(
        rg("exposure", "Exposure (EV)", -9.0, 9.0, 1.0, cur("exposure") ?: "0"),
        rg("zoom", "Zoom", 1.0, 8.0, 0.1, cur("zoom") ?: "1.0")
    ))
}
