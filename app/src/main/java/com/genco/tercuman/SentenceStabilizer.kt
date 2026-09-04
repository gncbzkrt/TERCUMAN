package com.genco.tercuman

/**
 * Lightweight, fully local sentence gate for streaming English ASR.
 *
 * v1.2.0 goals:
 * - Do not translate unstable partial ASR.
 * - Do not wait forever for punctuation.
 * - Keep natural sentences together.
 * - Prevent very long ASR blocks from accumulating indefinitely.
 */
object SentenceStabilizer {

    private val punctuationSplit =
        Regex("(?<=[.!?])\\s+")

    private val answerStart =
        Regex(
            "\\b(yes|no|sure|okay|ok|alright|certainly|of course)\\b",
            RegexOption.IGNORE_CASE
        )

    private val questionStart =
        Regex(
            "^\\b(do|does|did|can|could|will|would|shall|should|is|are|am|was|were|have|has|had|what|where|when|why|how|who|whom|which)\\b",
            RegexOption.IGNORE_CASE
        )

    /*
     * Words that strongly suggest the utterance is continuing.
     */
    private val continuationEnd =
        Regex(
            "\\b(a|an|the|and|or|but|because|if|to|of|for|from|with|without|in|on|at|by|as|than|that|this|these|those|very|really|quite|too|more|most|so|such|can|could|will|would|should|must|have|has|had|is|are|am|was|were|been|being|near|about|like|into|onto)\\s*$",
            RegexOption.IGNORE_CASE
        )

    private const val MAX_PENDING_WORDS = 12

    fun splitAndNormalize(raw: String): List<String> {
        val clean = raw
            .replace(Regex("\\s+"), " ")
            .trim()

        if (clean.isBlank()) {
            return emptyList()
        }

        val punctuated = clean
            .split(punctuationSplit)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val result = mutableListOf<String>()

        for (piece in punctuated) {
            result += splitObviousAnswer(piece)
        }

        return result
            .map { normalizePunctuation(it) }
            .filter { it.isNotBlank() }
    }

    /**
     * True means that an endpoint is probably only a temporary
     * pause inside the current sentence.
     *
     * This function deliberately has a hard maximum. A bad/slow
     * punctuation result must never cause an unlimited pending buffer.
     */
    fun looksIncomplete(text: String): Boolean {
        val s = text
            .replace(Regex("\\s+"), " ")
            .trim()

        if (s.isBlank()) {
            return true
        }

        /*
         * Explicit punctuation is the strongest completion signal.
         */
        if (
            s.endsWith(".") ||
            s.endsWith("?") ||
            s.endsWith("!")
        ) {
            return false
        }

        val words = s.split(" ")

        /*
         * Very short fragments are generally unsafe to translate.
         */
        if (words.size <= 2) {
            return false
        }

        /*
         * If ASR has already produced a long fragment, do not allow
         * pendingPrefix to grow into a giant paragraph.
         */
        if (words.size >= MAX_PENDING_WORDS) {
            return false
        }

        /*
         * A direct question is normally complete enough to translate.
         */
        if (questionStart.containsMatchIn(s)) {
            return false
        }

        /*
         * A fragment ending in a continuation word is likely to
         * continue after a short pause.
         */
        if (continuationEnd.containsMatchIn(s)) {
            return true
        }

        /*
         * Auxiliary at the end is another strong continuation signal.
         */
        val last = words.last().lowercase()

        if (
            last in setOf(
                "do",
                "does",
                "did",
                "is",
                "are",
                "am",
                "was",
                "were",
                "have",
                "has",
                "had",
                "can",
                "could",
                "will",
                "would",
                "should",
                "must"
            )
        ) {
            return true
        }

        return false
    }

    private fun splitObviousAnswer(text: String): List<String> {
        if (
            text.contains("?") ||
            text.contains("!")
        ) {
            return listOf(text)
        }

        val match = answerStart.find(text)
            ?: return listOf(text)

        val index = match.range.first

        /*
         * Avoid creating tiny fragments from the beginning of a sentence.
         */
        if (index <= 3 || index >= 12) {
            return listOf(text)
        }

        val before = text
            .substring(0, index)
            .trim()

        val answer = text
            .substring(index)
            .trim()

        return if (
            before.isNotBlank() &&
            answer.isNotBlank()
        ) {
            listOf(before, answer)
        } else {
            listOf(text)
        }
    }

    private fun normalizePunctuation(text: String): String {
        val s = text
            .trim()
            .trimEnd(',', ';', ':', ' ')

        if (s.isBlank()) {
            return s
        }

        if (
            s.endsWith(".") ||
            s.endsWith("?") ||
            s.endsWith("!")
        ) {
            return s
        }

        return if (questionStart.containsMatchIn(s)) {
            "$s?"
        } else {
            "$s."
        }
    }
}
