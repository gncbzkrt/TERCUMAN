package com.genco.tercuman

/**
 * Lightweight local conversation formatter.
 * It does not perform biometric speaker diarization; it infers turns from
 * punctuation/question-answer structure and preserves A/B order.
 */
object ConversationEngine {
    data class Turn(val text: String, val speaker: String)

    private val questionCue = Regex(
        "\\b(do|does|did|can|could|will|would|shall|should|is|are|am|was|were|have|has|had|what|where|when|why|how|who|whom|which)\\b",
        RegexOption.IGNORE_CASE
    )
    private val answerCue = Regex(
        "^(yes|no|sure|okay|ok|alright|certainly|of course|maybe|probably|thanks|thank you|great|fine|sorry)\\b",
        RegexOption.IGNORE_CASE
    )
    private val strongBoundary = Regex(
        "\\s+(?=(what|where|when|why|how|who|which|do you|does|did|can you|could you|will you|would you|have you|are you|is it)\\b)",
        RegexOption.IGNORE_CASE
    )

    fun format(raw: String): List<Turn> {
        if (raw.isBlank()) return emptyList()
        val normalized = raw.replace(Regex("\\s+"), " ").trim()
        val firstPass = SentenceStabilizer.splitAndNormalize(normalized)
        val pieces = firstPass.flatMap { splitEmbeddedTurns(it) }
            .map { it.trim() }
            .filter { it.length >= 2 }
        if (pieces.isEmpty()) return emptyList()

        val turns = mutableListOf<Turn>()
        var previousWasQuestion = false
        for (piece in pieces) {
            val isQuestion = piece.endsWith("?") || questionCue.containsMatchIn(piece.take(40))
            val isAnswer = answerCue.containsMatchIn(piece)
            val speaker = when {
                turns.isEmpty() -> "A"
                previousWasQuestion && isAnswer -> "B"
                previousWasQuestion -> "B"
                isQuestion -> "A"
                else -> if (turns.last().speaker == "A") "B" else "A"
            }
            turns += Turn(piece, speaker)
            previousWasQuestion = isQuestion
        }
        return turns
    }

    private fun splitEmbeddedTurns(text: String): List<String> {
        if (text.contains("?") || text.length < 28) return listOf(text)
        val match = strongBoundary.find(text) ?: return listOf(text)
        val index = match.range.first
        if (index < 12 || index > text.length - 8) return listOf(text)
        val before = text.substring(0, index).trim()
        val after = text.substring(index).trim()
        if (before.split(" ").size < 3 || after.split(" ").size < 2) return listOf(text)
        return listOf(before, after)
    }
}
