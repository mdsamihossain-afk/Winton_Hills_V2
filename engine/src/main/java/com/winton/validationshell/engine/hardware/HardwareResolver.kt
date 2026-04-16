package com.winton.validationshell.engine.hardware

import android.media.AudioDeviceInfo
import android.os.Build
import android.util.Log

/**
 * Resolves the current audio output route + device model into a [HardwareProfile].
 *
 * Resolution order:
 *  1. Detect the output route type from [AudioDeviceInfo] list.
 *  2. Query the [ProfileDatabase] for a device-specific or route-specific entry.
 *  3. If no DB match, fall back to safe built-in defaults.
 */
object HardwareResolver {

    private const val TAG = "HardwareResolver"

    /**
     * Resolve the output route from a list of [AudioDeviceInfo] and build model strings.
     * Returns the most relevant [HardwareProfile].
     *
     * The [ProfileDatabase] must have been loaded before calling this (via
     * [ProfileDatabase.load]). If it hasn't been loaded, built-in defaults are used.
     */
    fun resolve(
        outputDevices: Array<AudioDeviceInfo>,
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL
    ): HardwareProfile {
        val routeType = detectRouteType(outputDevices)

        // Try the JSON profile database first
        val dbEntry = ProfileDatabase.match(routeType, manufacturer, model)
        if (dbEntry != null) {
            Log.d(TAG, "Matched DB profile '${dbEntry.id}' for $manufacturer $model [$routeType]")
            return HardwareProfile(
                routeType = routeType,
                latencyClass = dbEntry.latencyClass,
                suggestedBufferFrames = dbEntry.bufferFrames,
                suggestedSampleRate = dbEntry.sampleRate,
                forceConservative = dbEntry.forceConservative
            )
        }

        // No DB match — use safe built-in defaults
        Log.d(TAG, "No DB match for $manufacturer $model [$routeType] — using built-in default")
        return builtInDefault(routeType)
    }

    // ========================================================================
    // BUILT-IN DEFAULTS — always-available fallback when DB is empty or missing
    // ========================================================================

    private fun builtInDefault(routeType: HardwareProfile.RouteType): HardwareProfile {
        return when (routeType) {
            HardwareProfile.RouteType.SPEAKER -> HardwareProfile(
                routeType = routeType,
                latencyClass = HardwareProfile.LatencyClass.NORMAL,
                suggestedBufferFrames = 256,
                suggestedSampleRate = 48000,
                forceConservative = false
            )
            HardwareProfile.RouteType.WIRED -> HardwareProfile(
                routeType = routeType,
                latencyClass = HardwareProfile.LatencyClass.LOW,
                suggestedBufferFrames = 256,
                suggestedSampleRate = 48000,
                forceConservative = false
            )
            HardwareProfile.RouteType.BLUETOOTH -> HardwareProfile(
                routeType = routeType,
                latencyClass = HardwareProfile.LatencyClass.HIGH,
                suggestedBufferFrames = 1024,
                suggestedSampleRate = 48000,
                forceConservative = true  // BT always conservative
            )
            HardwareProfile.RouteType.USB -> HardwareProfile(
                routeType = routeType,
                latencyClass = HardwareProfile.LatencyClass.NORMAL,
                suggestedBufferFrames = 512,
                suggestedSampleRate = 48000,
                forceConservative = false
            )
            HardwareProfile.RouteType.UNKNOWN -> HardwareProfile.DEFAULT
        }
    }

    // ========================================================================
    // ROUTE DETECTION
    // ========================================================================

    /**
     * Map the first recognized output device type to a [HardwareProfile.RouteType].
     * Priority: wired > USB > Bluetooth > speaker (most-specific first).
     */
    private fun detectRouteType(devices: Array<AudioDeviceInfo>): HardwareProfile.RouteType {
        // Prioritised scan: more specific routes first
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> return HardwareProfile.RouteType.WIRED
                else -> { /* continue */ }
            }
        }
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> return HardwareProfile.RouteType.USB
                else -> { /* continue */ }
            }
        }
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return HardwareProfile.RouteType.BLUETOOTH
                else -> { /* continue */ }
            }
        }
        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> return HardwareProfile.RouteType.SPEAKER
                else -> { /* continue */ }
            }
        }
        return HardwareProfile.RouteType.UNKNOWN
    }
}

