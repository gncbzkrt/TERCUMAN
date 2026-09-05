package com.genco.tercuman

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Cihaz-içi ML Kit ceviri motoru.
 *
 * v0.9: Ceviri modeli her cumlede yeniden indirilmez.
 * Once hazirlanir, sonra ayni Translator tekrar kullanilir.
 * Model indirme/ceviri sonsuza kadar beklemez; UI'ya hata dondurur.
 */
class TranslationEngine {
    data class LanguageRoute(val source: String, val target: String)

    private val languageId = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()
    private val preparedRoutes = mutableSetOf<String>()
    private val routeLocks = mutableMapOf<String, Mutex>()

    suspend fun prepareEnglishTurkish(onStatus: (String) -> Unit = {}) {
        ensureRouteModel("en", "tr", onStatus)
        onStatus("İngilizce → Türkçe modeli hazır ✓")
    }

    suspend fun translateEnglishToTurkish(text: String): String {
        return translate(text, LanguageRoute("en", "tr"))
    }

    /** Generic route API reserved for the upcoming two-way A↔B conversation mode. */
    suspend fun translate(text: String, route: LanguageRoute): String {
        if (text.isBlank()) return ""
        return translateWith(route.source, route.target, text) { }
    }

    suspend fun detectLanguage(text: String): String {
        if (text.isBlank()) return "und"
        return withTimeout(10_000L) { languageId.identifyLanguage(text).await() }
    }

    fun isSupportedLanguage(tag: String): Boolean =
        TranslateLanguage.fromLanguageTag(tag) != null

    suspend fun detectSupportedLanguage(text: String): String {
        if (text.isBlank()) return "und"
        return withTimeout(10_000L) { languageId.identifyLanguage(text).await() }
    }

    suspend fun translateConversationTurn(
        text: String,
        peerLanguage: String?,
        onStatus: (String) -> Unit = {}
    ): Pair<String, LanguageRoute> {
        if (text.isBlank()) return "" to LanguageRoute("und", "tr")
        val detected = detectSupportedLanguage(text)
        val source = if (detected == "und") "en" else detected
        if (source == "tr") {
            val target = peerLanguage
                ?.takeIf { it != "tr" && isSupportedLanguage(it) }
                ?: "en"
            return translateWith("tr", target, text, onStatus) to LanguageRoute("tr", target)
        }

        // First non-Turkish language becomes the peer language for the
        // opposite direction. Foreign speech always goes to Turkish.
        return translateWith(source, "tr", text, onStatus) to LanguageRoute(source, "tr")
    }

    suspend fun toTurkish(text: String, onStatus: (String) -> Unit = {}): Pair<String, String> {
        if (text.isBlank()) return "" to "und"

        val detected = withTimeout(10_000L) {
            languageId.identifyLanguage(text).await()
        }
        val source = if (detected == "und") "en" else detected
        if (source == "tr") return text to source

        val supported = TranslateLanguage.fromLanguageTag(source)
        if (supported == null) {
            // Whisper'ın belirsiz/desteklenmeyen dil etiketlerinde İngilizce'yi
            // güvenli varsayılan olarak kullan.
            return translateWith("en", "tr", text, onStatus) to "en"
        }

        return translateWith(source, "tr", text, onStatus) to source
    }

    private suspend fun translateWith(
        sourceTag: String,
        targetTag: String,
        text: String,
        onStatus: (String) -> Unit
    ): String {
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: TranslateLanguage.ENGLISH
        val target = TranslateLanguage.fromLanguageTag(targetTag) ?: TranslateLanguage.TURKISH
        check(source != target) { "Kaynak ve hedef dil aynı olamaz." }
        val key = "${sourceTag.lowercase()}-${targetTag.lowercase()}"
        val translator = synchronized(translators) {
            translators.getOrPut(key) {
                TranslatorOptions.Builder()
                    .setSourceLanguage(source)
                    .setTargetLanguage(target)
                    .build()
                    .let { Translation.getClient(it) }
            }
        }

        ensureRouteModel(sourceTag, targetTag, onStatus)
        onStatus("Metin çevriliyor…")
        return withTimeout(15_000L) {
            translator.translate(text).await()
        }
    }

    private suspend fun ensureRouteModel(
        sourceTag: String,
        targetTag: String,
        onStatus: (String) -> Unit
    ) {
        val key = "${sourceTag.lowercase()}-${targetTag.lowercase()}"
        val translator = synchronized(translators) {
            translators.getOrPut(key) { createTranslator(sourceTag, targetTag) }
        }
        val lock = synchronized(routeLocks) { routeLocks.getOrPut(key) { Mutex() } }
        lock.withLock {
            val alreadyPrepared = synchronized(preparedRoutes) { preparedRoutes.contains(key) }
            if (alreadyPrepared) return@withLock

            onStatus("Çeviri modeli hazırlanıyor…")
            withTimeout(60_000L) { translator.downloadModelIfNeeded().await() }
            synchronized(preparedRoutes) { preparedRoutes.add(key) }
        }
    }

    private fun createTranslator(sourceTag: String, targetTag: String = "tr"): Translator {
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: TranslateLanguage.ENGLISH
        val target = TranslateLanguage.fromLanguageTag(targetTag) ?: TranslateLanguage.TURKISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(target)
            .build()
        return Translation.getClient(options)
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        synchronized(preparedRoutes) { preparedRoutes.clear() }
        synchronized(routeLocks) { routeLocks.clear() }
        languageId.close()
    }
}
