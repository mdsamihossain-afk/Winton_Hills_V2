package com.winton.validationshell.engine.autoeq

import android.util.Log
import com.winton.validationshell.engine.policy.BiquadBandConfig

/**
 * Maps AutoEq PEQ filters to engine band config.
 * Shelf filters are skipped unless/until the DSP path supports explicit shelf types.
 */
object EqProfileMapper {

    private const val TAG = "EqProfileMapper"

    fun mapToBiquadBands(profile: AutoEqProfile): List<BiquadBandConfig> {
        val bands = mutableListOf<BiquadBandConfig>()
        profile.peqFilters.forEach { filter ->
            if (filter.type == AutoEqFilterType.PEAK) {
                bands.add(
                    BiquadBandConfig(
                        frequencyHz = filter.fcHz,
                        gainDb = filter.gainDb,
                        q = filter.q
                    )
                )
            } else {
                Log.d(TAG, "Skipping unsupported filter type ${filter.type} for profile ${profile.id}")
            }
        }
        return bands
    }
}

