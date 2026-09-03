package com.genco.tercuman

import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap

class TranslationEngine {
    private val languageId = LanguageIdentification.getClient()
    private val translators = ConcurrentHashMap<String, Translator>()
    private val createMutex = Mutex()

    suspend fun toTurkish(text: String): Pair<String, String> {
        if (text.isBlank()) return "" to "und"

        val detected = languageId.identifyLanguage(text).await()
        val source = if (detected == "und") "en" else detected
        if (source == "tr") return text to source

        val translator = getTranslator(source)
        return translator.translate(text).await() to source
    }

    private suspend fun getTranslator(source: String): Translator {
        translators[source]?.let { return it }

        return createMutex.withLock {
            translators[source]?.let { return@withLock it }

            val src = TranslateLanguage.fromLanguageTag(source)
                ?: TranslateLanguage.ENGLISH
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(src)
                .setTargetLanguage(TranslateLanguage.TURKISH)
                .build()
            val translator = Translation.getClient(options)
            try {
                // Model yalnızca ilk kullanımda indirilir ve sonrasında açık tutulur.
                // v0.2'de her 2 saniyelik parçada tekrar downloadModelIfNeeded çağrılıyordu.
                translator.downloadModelIfNeeded().await()
                translators[source] = translator
                translator
            } catch (e: Exception) {
                translator.close()
                throw e
            }
        }
    }

    fun close() {
        translators.values.forEach { runCatching { it.close() } }
        translators.clear()
        languageId.close()
    }
}
