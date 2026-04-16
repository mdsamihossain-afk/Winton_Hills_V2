package com.winton.validationshell.engine.autoeq

/**
 * Runtime representation of an AutoEq profile payload record.
 */
data class AutoEqProfile(
    val id: String,
    val manufacturer: String,
    val name: String,
    val normalizedName: String,
    val source: String,
    val form: String?,
    val preampDb: Float?,
    val peqFilters: List<AutoEqPeqFilter>,
    val tags: List<String>,
    val aliases: List<String>
)

enum class AutoEqFilterType {
    PEAK,
    LOW_SHELF,
    HIGH_SHELF
}

data class AutoEqPeqFilter(
    val type: AutoEqFilterType,
    val fcHz: Float,
    val q: Float,
    val gainDb: Float
)

data class AutoEqMatch(
    val requestedName: String,
    val normalizedRequest: String,
    val profile: AutoEqProfile?,
    val matchSource: String,
    val confidence: Float,
    val fallbackUsed: Boolean,
    val reason: String? = null
)

