package com.winton.validationshell.engine.autoeq

/**
 * Conservative matcher: exact normalized match first, then exact alias match.
 * No fuzzy matching in Phase-1 to avoid wrong profile application.
 */
object AutoEqProfileMatcher {

    fun suggestByName(query: String, limit: Int = 8): List<String> {
        val normalizedQuery = AutoEqNameNormalizer.normalize(query)
        if (normalizedQuery.isEmpty()) return emptyList()

        val scored = mutableMapOf<String, Int>()
        AutoEqProfileRepository.allProfiles().forEach { profile ->
            val displayName = "${profile.manufacturer} ${profile.name}".trim()
            var bestScore = scoreMatch(normalizedQuery, profile.normalizedName)

            profile.aliases.forEach { alias ->
                val aliasScore = scoreMatch(normalizedQuery, AutoEqNameNormalizer.normalize(alias))
                if (aliasScore > bestScore) bestScore = aliasScore
            }

            if (bestScore > 0) {
                val existing = scored[displayName] ?: Int.MIN_VALUE
                if (bestScore > existing) scored[displayName] = bestScore
            }
        }

        return scored.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() })
            .map { it.key }
            .take(limit.coerceIn(1, 20))
    }

    fun matchByName(requestedName: String): AutoEqMatch {
        val normalized = AutoEqNameNormalizer.normalize(requestedName)
        if (normalized.isEmpty()) {
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

        AutoEqProfileRepository.findByNormalizedName(normalized)?.let {
            return AutoEqMatch(
                requestedName = requestedName,
                normalizedRequest = normalized,
                profile = it,
                matchSource = "normalized_exact",
                confidence = 1f,
                fallbackUsed = false
            )
        }

        val aliasMatch = AutoEqProfileRepository.findByNormalizedAlias(normalized)
        if (aliasMatch != null) {
            return AutoEqMatch(
                requestedName = requestedName,
                normalizedRequest = normalized,
                profile = aliasMatch,
                matchSource = "alias_exact",
                confidence = 0.95f,
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
            reason = "no_exact_match"
        )
    }

    private fun scoreMatch(query: String, target: String): Int {
        if (query == target) return 420

        val queryTokens = query.split(' ').filter { it.isNotBlank() }
        val targetTokens = target.split(' ').filter { it.isNotBlank() }
        if (queryTokens.isEmpty() || targetTokens.isEmpty()) return 0

        var score = 0

        // Strong exact/prefix behavior first.
        if (target.startsWith(query)) score = maxOf(score, 320)
        if (queryTokens.all { q -> targetTokens.any { t -> t.startsWith(q) } }) score = maxOf(score, 260)
        if (target.contains(query)) score = maxOf(score, 180)

        // Brand-first boost: first token in target usually maps to manufacturer.
        val queryFirst = queryTokens.first()
        val targetBrand = targetTokens.first()
        if (targetBrand.startsWith(queryFirst)) score = maxOf(score, 300)
        if (isOneEditOrEqual(queryFirst, targetBrand)) score = maxOf(score, 235)

        // Typo-tolerant token match (bounded edit distance of 1) for suggestions only.
        if (queryTokens.all { q -> targetTokens.any { t -> isOneEditOrEqual(q, t) || t.startsWith(q) } }) {
            score = maxOf(score, 170)
        }

        return score
    }

    private fun isOneEditOrEqual(a: String, b: String): Boolean {
        if (a == b) return true
        val lenA = a.length
        val lenB = b.length
        if (kotlin.math.abs(lenA - lenB) > 1) return false

        var i = 0
        var j = 0
        var edits = 0
        while (i < lenA && j < lenB) {
            if (a[i] == b[j]) {
                i++
                j++
                continue
            }

            edits++
            if (edits > 1) return false

            when {
                lenA > lenB -> i++
                lenB > lenA -> j++
                else -> {
                    i++
                    j++
                }
            }
        }

        if (i < lenA || j < lenB) edits++
        return edits <= 1
    }
}

