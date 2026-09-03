package com.genco.tercuman

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

class TranslationEngine {
    private val languageId = LanguageIdentification.getClient()

    suspend fun toTurkish(text: String): Pair<String, String> {
        if (text.isBlank()) return "" to "und"
        val detected = languageId.identifyLanguage(text).await()
        val source = if (detected == "und") "en" else detected
        if (source == "tr") return text to source
        val src = TranslateLanguage.fromLanguageTag(source) ?: TranslateLanguage.ENGLISH
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(src)
            .setTargetLanguage(TranslateLanguage.TURKISH)
            .build()
        val translator = Translation.getClient(options)
        return try {
            translator.downloadModelIfNeeded().await()
            translator.translate(text).await() to source
        } finally {
            translator.close()
        }
    }

    fun close() = languageId.close()
}
