package com.winton.validationshell.engine.hardware

/**
 * Describes the audio capabilities and constraints for a detected output route.
 * Used by the engine to select buffer sizes, sample rates, and conservative behavior.
 */
data class HardwareProfile(
    val routeType: RouteType,
    val latencyClass: LatencyClass,
    val suggestedBufferFrames: Int,
    val suggestedSampleRate: Int,
    val forceConservative: Boolean
) {
    enum class RouteType { SPEAKER, WIRED, BLUETOOTH, USB, UNKNOWN }
    enum class LatencyClass { LOW, NORMAL, HIGH }

    companion object {
        /** Safe default for unknown hardware. */
        val DEFAULT = HardwareProfile(
            routeType = RouteType.UNKNOWN,
            latencyClass = LatencyClass.HIGH,
            suggestedBufferFrames = 1024,
            suggestedSampleRate = 48000,
            forceConservative = true
        )
    }
}

