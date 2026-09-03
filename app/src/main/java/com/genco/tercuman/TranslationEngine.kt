package com.genco.tercuman

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/**
 * Cihaz-içi ML Kit ceviri motoru.
 *
 * v0.9: Ceviri modeli her cumlede yeniden indirilmez.
 * Once hazirlanir, sonra ayni Translator tekrar kullanilir.
 * Model indirme/ceviri sonsuza kadar beklemez; UI'ya hata dondurur.
 */
class TranslationEngine {
    private val languageId = LanguageIdentification.getClient()
    private val translators = mutableMapOf<String, Translator>()

    suspend fun prepareEnglishTurkish(onStatus: (String) -> Unit = {}) {
        val key = "en-tr"
        val translator = translators.getOrPut(key) { createTranslator("en") }
        onStatus("İngilizce → Türkçe modeli kontrol ediliyor…")
        withTimeout(60_000L) {
            translator.downloadModelIfNeeded().await()
        }
        onStatus("İngilizce → Türkçe modeli hazır ✓")
    }

    suspend fun toTurkish(text: String, onStatus: (String) -> Unit = {}): Pair<String, String> {
        if (text.isBlank()) return "" to "en"

        // v0.10: TERCUMAN'in mevcut test/ana senaryosu Ingilizce kaynak iceriktir.
        // Whisper da language="en" ile zorlandigi icin ML Kit Language ID'nin
        // kisa parcalari Japonca vb. yanlis siniflandirmasi ceviriyi bozmaz.
        return translateWith("en", text, onStatus) to "en"
    }

    private suspend fun translateWith(sourceTag: String, text: String, onStatus: (String) -> Unit): String {
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: TranslateLanguage.ENGLISH
        val sourceKey = source
        val key = "$sourceKey-tr"
        val translator = translators.getOrPut(key) {
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
                .let { Translation.getClient(it) }
        }

        onStatus("Çeviri modeli hazır değilse hazırlanıyor…")
        withTimeout(60_000L) {
            translator.downloadModelIfNeeded().await()
        }
        onStatus("Metin Türkçeye çevriliyor…")
        return withTimeout(15_000L) {
            translator.translate(text).await()
        }
    }

    private fun createTranslator(sourceTag: String): Translator {
        val source = TranslateLanguage.fromLanguageTag(sourceTag) ?: TranslateLanguage.ENGLISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(source)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        return Translation.getClient(options)
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        languageId.close()
    }
}
