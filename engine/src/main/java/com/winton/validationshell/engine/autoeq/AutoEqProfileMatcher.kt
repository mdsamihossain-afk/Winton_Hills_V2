package com.winton.validationshell.engine.autoeq

/**
 * Conservative matcher: exact normalized match first, then exact alias match.
 * No fuzzy matching in Phase-1 to avoid wrong profile application.
 */
object AutoEqProfileMatcher {

    /**
     * Aggressive normalization for fuzzy matching.
     * Strips all non-alphanumeric characters and spaces.
     */
    private fun fuzzyNormalize(value: String): String {
        return value.lowercase().filter { it.isLetterOrDigit() }
    }

    fun suggestByName(query: String, limit: Int = 5): List<String> {
        val normalizedQuery = fuzzyNormalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val profiles = AutoEqProfileRepository.allProfiles()
        val results = profiles.map { profile ->
            val displayName = "${profile.manufacturer} ${profile.name}".trim()
            val normalizedTarget = fuzzyNormalize(displayName)
            
            // Calculate distance to full name
            var minDistance = levenshteinDistance(normalizedQuery, normalizedTarget)
            
            // Also check distance to just the model name
            val modelDistance = levenshteinDistance(normalizedQuery, fuzzyNormalize(profile.name))
            if (modelDistance < minDistance) minDistance = modelDistance

            // And aliases
            profile.aliases.forEach { alias ->
                val aliasDistance = levenshteinDistance(normalizedQuery, fuzzyNormalize(alias))
                if (aliasDistance < minDistance) minDistance = aliasDistance
            }

            displayName to minDistance
        }

        return results
            .sortedBy { it.second }
            .take(limit.coerceIn(1, 20))
            .map { it.first }
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                if (s1[i - 1] == s2[j - 1]) {
                    dp[j] = prev
                } else {
                    dp[j] = 1 + minOf(prev, minOf(dp[j - 1], dp[j]))
                }
                prev = temp
            }
        }
        return dp[s2.length]
    }

    fun matchByName(requestedName: String): AutoEqMatch {
        val normalized = AutoEqNameNormalizer.normalize(requestedName)
        val fuzzyReq = fuzzyNormalize(requestedName)
        
        if (fuzzyReq.isEmpty()) {
            return AutoEqMatch(
                requestedName = requestedName,
                normalizedRequest = normalized,
                profile = null,
                matchSource = "none",
                confidence = 0f,
                fallbackUsed = true,
                reason = "empty_request"
            )
        }

        // 1. Exact matches (high confidence)
        AutoEqProfileRepository.findByNormalizedName(normalized)?.let {
            return AutoEqMatch(requestedName, normalized, it, "normalized_exact", 1f, false)
        }

        AutoEqProfileRepository.findByNormalizedAlias(normalized)?.let {
            return AutoEqMatch(requestedName, normalized, it, "alias_exact", 0.95f, false)
        }

        // 2. Fuzzy match
        val profiles = AutoEqProfileRepository.allProfiles()
        var bestProfile: com.winton.validationshell.engine.autoeq.AutoEqProfile? = null
        var minDistance = Int.MAX_VALUE
        var bestMatchSource = "fuzzy"

        for (profile in profiles) {
            val targetFullName = fuzzyNormalize("${profile.manufacturer} ${profile.name}")
            val targetModel = fuzzyNormalize(profile.name)
            
            val dFull = levenshteinDistance(fuzzyReq, targetFullName)
            val dModel = levenshteinDistance(fuzzyReq, targetModel)
            
            val d = minOf(dFull, dModel)
            if (d < minDistance) {
                minDistance = d
                bestProfile = profile
            }
            
            for (alias in profile.aliases) {
                val dAlias = levenshteinDistance(fuzzyReq, fuzzyNormalize(alias))
                if (dAlias < minDistance) {
                    minDistance = dAlias
                    bestProfile = profile
                    bestMatchSource = "fuzzy_alias"
                }
            }
        }

        // Confidence heuristic: 1.0 for distance 0, dropping as distance increases relative to length
        val confidence = if (bestProfile != null) {
            val maxLen = maxOf(fuzzyReq.length, 1)
            (1.0f - (minDistance.toFloat() / maxLen)).coerceIn(0f, 1f)
        } else 0f

        // Only accept if confidence is reasonably high (e.g. > 0.7)
        if (bestProfile != null && confidence > 0.7f) {
            return AutoEqMatch(
                requestedName = requestedName,
                normalizedRequest = normalized,
                profile = bestProfile,
                matchSource = bestMatchSource,
                confidence = confidence,
                fallbackUsed = false
            )
        }

        return AutoEqMatch(
            requestedName = requestedName,
            normalizedRequest = normalized,
            profile = null,
            matchSource = "none",
            confidence = 0f,
            fallbackUsed = true,
            reason = "no_confident_match"
        )
    }

}

