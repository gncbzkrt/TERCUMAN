package com.genco.tercuman

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * TERCÜMAN v1.3 AI Conversation Engine.
 *
 * The local LLM is deliberately used as a short-lived decision engine,
 * not as the translator. It decides whether a streaming ASR candidate
 * is a complete human-sized utterance, should wait for more speech,
 * or should be merged with the previous pending fragment.
 *
 * No network/API key is used by this class.
 */
class AIConversationEngine(
    private val context: Context,
    private val modelFile: File
) {
    enum class Action { COMMIT, WAIT }

    data class Decision(
        val action: Action,
        val sentence: String
    )

    private val callMutex = Mutex()
    private var engine: Engine? = null

    suspend fun start() = callMutex.withLock {
        if (engine != null) return@withLock
        engine = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
                maxNumTokens = 96
            )
        ).also { it.initialize() }
    }

    suspend fun decide(
        candidate: String,
        previousPending: String = ""
    ): Decision = callMutex.withLock {
        val e = engine ?: return@withLock fallback(candidate, previousPending)

        val prompt = """
You are TERCÜMAN's sentence boundary controller.
You receive streaming speech recognition text in any supported language.
Your ONLY job is to decide whether the current spoken meaning unit is complete.

Return EXACTLY one line in one of these forms:
COMMIT|complete sentence
WAIT|
MERGE|complete sentence

Rules:
- Do not translate.
- Do not explain.
- Preserve the speaker's words; only fix obvious ASR spacing/capitalization.
- COMMIT when the meaning is naturally complete, including normal questions and short answers.
- WAIT when the speaker clearly started a thought that is unfinished.
- MERGE when previousPending is a fragment that must be joined to the current candidate.
- Never invent content.
- If candidate is already a long coherent sentence, COMMIT.
- If unsure, COMMIT rather than waiting forever.

previousPending:
${previousPending.ifBlank { "(none)" }}

current candidate:
$candidate
        """.trimIndent()

        try {
            val conversation = e.createConversation(
                ConversationConfig(
                    systemInstruction = Contents.of(
                        "You are a deterministic sentence boundary controller. Output only COMMIT, WAIT, or MERGE."
                    ),
                    samplerConfig = SamplerConfig(
                        temperature = 0.0,
                        topK = 1,
                        topP = 1.0
                    )
                )
            )

            try {
                val messages = conversation.sendMessageAsync(prompt)
                    .catch { throw it }
                    .toList()

                val response = messages.joinToString("") { it.toString() }.trim()
                parseDecision(response, candidate, previousPending)
            } finally {
                conversation.close()
            }
        } catch (_: Throwable) {
            fallback(candidate, previousPending)
        }
    }

    private fun parseDecision(
        response: String,
        candidate: String,
        previousPending: String
    ): Decision {
        val line = response
            .lineSequence()
            .map { it.trim() }
            .firstOrNull {
                it.startsWith("COMMIT|") ||
                it.startsWith("WAIT|") ||
                it.startsWith("MERGE|")
            } ?: return fallback(candidate, previousPending)

        val actionText = line.substringBefore('|')
        val payload = line.substringAfter('|', "").trim()

        return when (actionText) {
            "WAIT" -> Decision(Action.WAIT, "")
            "MERGE" -> Decision(
                Action.COMMIT,
                cleanSentence(payload.ifBlank { join(previousPending, candidate) })
            )
            else -> Decision(
                Action.COMMIT,
                cleanSentence(payload.ifBlank { candidate })
            )
        }
    }

    private fun fallback(candidate: String, previousPending: String): Decision {
        val joined = join(previousPending, candidate)
        val text = joined.trim()
        if (text.isBlank()) return Decision(Action.WAIT, "")

        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        val lower = text.lowercase()

        val obviousIncomplete =
            words.size < 3 ||
            lower.endsWith(" and") ||
            lower.endsWith(" or") ||
            lower.endsWith(" but") ||
            lower.endsWith(" because") ||
            lower.endsWith(" if") ||
            lower.endsWith(" to") ||
            lower.endsWith(" of") ||
            lower.endsWith(" for") ||
            lower.endsWith(" with") ||
            lower.endsWith(" can") ||
            lower.endsWith(" could") ||
            lower.endsWith(" would") ||
            lower.endsWith(" should") ||
            lower.endsWith(" will") ||
            lower.endsWith(" have") ||
            lower.endsWith(" has") ||
            lower.endsWith(" is") ||
            lower.endsWith(" are")

        if (obviousIncomplete && words.size < 14) {
            return Decision(Action.WAIT, "")
        }

        return Decision(Action.COMMIT, cleanSentence(text))
    }

    private fun join(a: String, b: String): String =
        listOf(a.trim(), b.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")

    private fun cleanSentence(s: String): String =
        s.replace(Regex("\\s+"), " ")
            .trim()
            .trim('|')
            .trim()

    fun close() {
        synchronized(this) {
            engine?.close()
            engine = null
        }
    }
}
