package com.genco.tercuman

/**
 * Streaming ASR'den gelen ham İngilizce metni, çeviri öncesi daha anlamlı
 * cümle parçalarına ayırır. Bu katman bir dil modeli değildir; özellikle
 * noktalama üretmeyen streaming ASR için hafif ve cihaz-içi bir stabilizer'dır.
 */
object SentenceStabilizer {
    private val punctuationSplit = Regex("(?<=[.!?])\\s+")
    private val answerStart = Regex("\\b(yes|no|sure|okay|ok|alright|certainly)\\b", RegexOption.IGNORE_CASE)
    private val questionStart = Regex(
        "^(do|does|did|can|could|will|would|shall|should|is|are|am|was|were|have|has|had|what|where|when|why|how|who|whom|which)\\b",
        RegexOption.IGNORE_CASE
    )

    fun splitAndNormalize(raw: String): List<String> {
        val clean = raw.replace(Regex("\\s+"), " ").trim()
        if (clean.isBlank()) return emptyList()

        val punctuated = clean.split(punctuationSplit)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()
        for (piece in punctuated) {
            result += splitObviousAnswer(piece)
        }
        return result.map { normalizePunctuation(it) }.filter { it.isNotBlank() }
    }

    private fun splitObviousAnswer(text: String): List<String> {
        if (text.contains("?") || text.contains("!")) return listOf(text)
        val match = answerStart.find(text) ?: return listOf(text)
        val index = match.range.first
        // Başta bulunan "yes/no" zaten ayrı bir cevap olabilir.
        if (index <= 3) return listOf(text)
        // Çok kısa metinleri gereksiz bölme.
        if (index < 12) return listOf(text)
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
