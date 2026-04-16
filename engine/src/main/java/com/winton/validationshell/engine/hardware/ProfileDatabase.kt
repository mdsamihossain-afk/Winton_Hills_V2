package com.winton.validationshell.engine.hardware

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * A single entry from the local `hardware_profiles.json` asset.
 * Each entry describes expected behavior for a manufacturer/model + route combination.
 */
data class DeviceProfileEntry(
    val id: String,
    val manufacturerMatch: String?,
    val modelPattern: Regex?,
    val route: HardwareProfile.RouteType?,
    val latencyClass: HardwareProfile.LatencyClass,
    val bufferFrames: Int,
    val sampleRate: Int,
    val forceConservative: Boolean,
    val isFallback: Boolean
)

/**
 * Loads and queries the local hardware-profile JSON database shipped as an engine asset.
 *
 * Lifecycle: call [load] once (e.g. from `Engine.init` or the first `resolveHardware` call).
 * After that, [match] is a fast in-memory lookup.
 */
object ProfileDatabase {

    private const val TAG = "ProfileDatabase"
    private const val ASSET_NAME = "hardware_profiles.json"

    private var entries: List<DeviceProfileEntry> = emptyList()
    private var loaded = false

    /**
     * Load profiles from the engine module's assets.
     * Safe to call multiple times; only the first call reads disk.
     */
    fun load(context: Context) {
        if (loaded) return
        try {
            val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.getJSONArray("profiles")
            val result = mutableListOf<DeviceProfileEntry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val matchObj = obj.optJSONObject("match")
                result.add(
                    DeviceProfileEntry(
                        id = obj.getString("id"),
                        manufacturerMatch = matchObj?.optString("manufacturer")
                            ?.takeIf { it.isNotBlank() },
                        modelPattern = matchObj?.optString("modelPattern")
                            ?.takeIf { it.isNotBlank() }
                            ?.let { Regex(it, RegexOption.IGNORE_CASE) },
                        route = obj.optString("route").takeIf { it.isNotBlank() }
                            ?.let { runCatching { HardwareProfile.RouteType.valueOf(it) }.getOrNull() },
                        latencyClass = obj.optString("latencyClass").let {
                            runCatching { HardwareProfile.LatencyClass.valueOf(it) }
                                .getOrDefault(HardwareProfile.LatencyClass.HIGH)
                        },
                        bufferFrames = obj.optInt("bufferFrames", 1024),
                        sampleRate = obj.optInt("sampleRate", 48000),
                        forceConservative = obj.optBoolean("forceConservative", false),
                        isFallback = obj.optBoolean("isFallback", false)
                    )
                )
            }
            entries = result
            loaded = true
            Log.i(TAG, "Loaded ${entries.size} hardware profiles from $ASSET_NAME")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $ASSET_NAME — will use built-in defaults", e)
            entries = emptyList()
            loaded = true // don't retry on every call
        }
    }

    /**
     * Find the best matching profile for the given device + route.
     *
     * Matching priority:
     *   1. Exact manufacturer + model-pattern + route
     *   2. Manufacturer-only + route
     *   3. Route-only (generic)
     *   4. Fallback entry
     *   5. `null` (caller should use its own default)
     */
    fun match(
        route: HardwareProfile.RouteType,
        manufacturer: String,
        model: String
    ): DeviceProfileEntry? {
        val mfr = manufacturer.lowercase()

        // Tier 1: manufacturer + model pattern + route
        entries.firstOrNull { e ->
            e.route == route
                && !e.isFallback
                && e.manufacturerMatch != null
                && mfr.contains(e.manufacturerMatch.lowercase())
                && e.modelPattern != null
                && e.modelPattern.containsMatchIn(model)
        }?.let { return it }

        // Tier 2: manufacturer only + route
        entries.firstOrNull { e ->
            e.route == route
                && !e.isFallback
                && e.manufacturerMatch != null
                && mfr.contains(e.manufacturerMatch.lowercase())
                && e.modelPattern == null
        }?.let { return it }

        // Tier 3: generic route match (no manufacturer constraint)
        entries.firstOrNull { e ->
            e.route == route
                && !e.isFallback
                && e.manufacturerMatch == null
                && e.modelPattern == null
        }?.let { return it }

        // Tier 4: explicit fallback entry
        entries.firstOrNull { it.isFallback }?.let { return it }

        return null
    }

    /** Whether the database has been loaded (even if empty). */
    fun isLoaded(): Boolean = loaded

    /** Number of loaded profiles. */
    fun size(): Int = entries.size

    /** Reset for testing. */
    internal fun reset() {
        entries = emptyList()
        loaded = false
    }
}

