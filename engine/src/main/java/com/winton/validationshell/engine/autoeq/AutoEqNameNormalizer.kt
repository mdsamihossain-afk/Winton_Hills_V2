package com.winton.validationshell.engine.autoeq

object AutoEqNameNormalizer {

    private val punctuationRegex = Regex("[^a-z0-9 ]")
    private val spacesRegex = Regex("\\s+")

    /**
     * Conservative normalization for lookup keys.
     * Keeps alnum + spaces, lowercases, and collapses repeated spaces.
     */
    fun normalize(value: String): String {
        val lower = value.lowercase()
        val noPunctuation = punctuationRegex.replace(lower, " ")
        return spacesRegex.replace(noPunctuation, " ").trim()
    }

    fun stableId(manufacturer: String, name: String): String {
        val normalized = normalize("$manufacturer $name")
        return normalized.replace(' ', '_')
    }
}

