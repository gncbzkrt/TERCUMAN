package com.genco.tercuman

/** Lightweight, fully local sentence gate for streaming English ASR. */
object SentenceStabilizer {
    private val punctuationSplit = Regex("(?<=[.!?])\\s+")
    private val answerStart = Regex("\\b(yes|no|sure|okay|ok|alright|certainly|of course)\\b", RegexOption.IGNORE_CASE)
    private val questionStart = Regex(
        "^(do|does|did|can|could|will|would|shall|should|is|are|am|was|were|have|has|had|what|where|when|why|how|who|whom|which)\\b",
        RegexOption.IGNORE_CASE
    )
    private val continuationEnd = Regex(
        "\\b(a|an|the|and|or|but|because|if|to|of|for|from|with|without|in|on|at|by|as|than|that|this|these|those|very|really|quite|too|more|most|so|such|can|could|will|would|should|must|have|has|had|is|are|was|were|am|been|being)\\s*$",
        RegexOption.IGNORE_CASE
    )

    fun splitAndNormalize(raw: String): List<String> {
        val clean = raw.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()
        val punctuated = clean.split(punctuationSplit).map { it.trim() }.filter { it.isNotBlank() }
        val result = mutableListOf<String>()
        for (piece in punctuated) result += splitObviousAnswer(piece)
        return result.map { normalizePunctuation(it) }.filter { it.isNotBlank() }
    }

    /** True when a pause/endpoint is likely only a temporary break inside a sentence. */
    fun looksIncomplete(text: String): Boolean {
        val s = text.replace(Regex("\\s+"), " ").trim()
        if (s.isBlank()) return true
        if (s.endsWith("...")) return true
        if (s.endsWith("?") || s.endsWith("!")) return false
        if (continuationEnd.containsMatchIn(s)) return true
        val words = s.split(" ")
        if (words.size <= 2) return false
        // A bare question/statement fragment with an auxiliary at the end is incomplete.
        val last = words.last().lowercase()
        if (last in setOf("do", "does", "did", "is", "are", "was", "were", "am", "have", "has", "had", "can", "could", "will", "would", "should", "must")) return true
        return false
    }

    private fun splitObviousAnswer(text: String): List<String> {
        if (text.contains("?") || text.contains("!")) return listOf(text)
        val match = answerStart.find(text) ?: return listOf(text)
        val index = match.range.first
        if (index <= 3 || index < 12) return listOf(text)
        val before = text.substring(0, index).trim()
        val answer = text.substring(index).trim()
        return if (before.isNotBlank() && answer.isNotBlank()) listOf(before, answer) else listOf(text)
    }

    private fun normalizePunctuation(text: String): String {
        val s = text.trim().trimEnd(',', ';', ':')
        if (s.isBlank() || s.endsWith('.') || s.endsWith('?') || s.endsWith('!')) return s
        return if (questionStart.containsMatchIn(s)) "$s?" else "$s."
    }
}
