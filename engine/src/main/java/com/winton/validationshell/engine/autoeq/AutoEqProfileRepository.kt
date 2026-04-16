package com.winton.validationshell.engine.autoeq

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads compact AutoEq runtime profiles from engine assets.
 */
object AutoEqProfileRepository {

    private const val TAG = "AutoEqRepository"
    private const val ASSET_NAME = "autoeq_profiles.json"

    @Volatile
    private var loaded = false

    private var profiles: List<AutoEqProfile> = emptyList()
    private var byNormalizedName: Map<String, AutoEqProfile> = emptyMap()
    private var byNormalizedAlias: Map<String, AutoEqProfile> = emptyMap()

    fun load(context: Context) {
        if (loaded) return
        try {
            val json = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            val root = JSONObject(json)
            val arr = root.optJSONArray("profiles") ?: JSONArray()
            val parsed = mutableListOf<AutoEqProfile>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                parseProfile(item)?.let { parsed.add(it) }
            }
            profiles = parsed.sortedWith(compareBy({ it.manufacturer.lowercase() }, { it.name.lowercase() }))
            byNormalizedName = profiles.associateBy { it.normalizedName }
            val aliases = mutableMapOf<String, AutoEqProfile>()
            profiles.forEach { profile ->
                profile.aliases.forEach { alias ->
                    val normalizedAlias = AutoEqNameNormalizer.normalize(alias)
                    if (normalizedAlias.isNotBlank() && !aliases.containsKey(normalizedAlias)) {
                        aliases[normalizedAlias] = profile
                    }
                }
            }
            byNormalizedAlias = aliases
            loaded = true
            Log.i(TAG, "Loaded ${profiles.size} AutoEq profiles")
        } catch (e: Exception) {
            loaded = true
            profiles = emptyList()
            byNormalizedName = emptyMap()
            byNormalizedAlias = emptyMap()
            Log.w(TAG, "Failed to load $ASSET_NAME. Continuing with neutral fallback.", e)
        }
    }

    fun isLoaded(): Boolean = loaded

    fun size(): Int = profiles.size

    fun findByNormalizedName(normalizedName: String): AutoEqProfile? = byNormalizedName[normalizedName]

    fun findByNormalizedAlias(normalizedAlias: String): AutoEqProfile? = byNormalizedAlias[normalizedAlias]

    fun allProfiles(): List<AutoEqProfile> = profiles

    internal fun resetForTests() {
        loaded = false
        profiles = emptyList()
        byNormalizedName = emptyMap()
        byNormalizedAlias = emptyMap()
    }

    private fun parseProfile(item: JSONObject): AutoEqProfile? {
        val manufacturer = item.optString("manufacturer").trim()
        val name = item.optString("name").trim()
        if (manufacturer.isEmpty() || name.isEmpty()) {
            return null
        }

        val id = item.optString("id").takeIf { it.isNotBlank() }
            ?: AutoEqNameNormalizer.stableId(manufacturer, name)

        val normalizedName = item.optString("normalized_name").takeIf { it.isNotBlank() }
            ?: AutoEqNameNormalizer.normalize("$manufacturer $name")

        return AutoEqProfile(
            id = id,
            manufacturer = manufacturer,
            name = name,
            normalizedName = normalizedName,
            source = item.optString("source", "AutoEq"),
            form = item.optString("form").takeIf { it.isNotBlank() },
            preampDb = item.optDouble("preamp_db", Double.NaN).let {
                if (it.isNaN()) null else it.toFloat()
            },
            peqFilters = parseFilters(item.optJSONArray("peq_filters")),
            tags = parseStringArray(item.optJSONArray("tags")),
            aliases = parseStringArray(item.optJSONArray("aliases"))
        )
    }

    private fun parseFilters(arr: JSONArray?): List<AutoEqPeqFilter> {
        if (arr == null) return emptyList()
        val result = mutableListOf<AutoEqPeqFilter>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val type = runCatching { AutoEqFilterType.valueOf(obj.optString("type")) }.getOrNull() ?: continue
            val fc = obj.optDouble("fc_hz", Double.NaN)
            val q = obj.optDouble("q", Double.NaN)
            val gain = obj.optDouble("gain_db", Double.NaN)
            if (fc.isNaN() || q.isNaN() || gain.isNaN()) continue
            if (fc <= 0.0 || fc > 24000.0 || q <= 0.0 || q > 20.0 || gain < -30.0 || gain > 30.0) continue
            result.add(
                AutoEqPeqFilter(
                    type = type,
                    fcHz = fc.toFloat(),
                    q = q.toFloat(),
                    gainDb = gain.toFloat()
                )
            )
        }
        return result
    }

    private fun parseStringArray(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val value = arr.optString(i).trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }
}

