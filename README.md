# TERCÜMAN v0.10.0

Stabilizasyon sürümü. Çalışan v0.1 ses→Whisper→ML Kit çeviri hattı geri getirildi. Mikrofon ve telefon sesi 4 saniyelik WAV parçalarıyla işlenir. Doğal Supertonic TTS çeviri akışından ayrılmıştır; ses önizlemesi Android Türkçe TTS ile güvenli test edilir.

İlk testte yalnızca Whisper modelini hazırlayın. Sesli çıktı kapalıyken DIŞ SES ve TELEFON SESİ seçeneklerini test edin.


v0.10.0 ÇEVİRİ STABİLİZASYONU
- İngilizce→Türkçe ML Kit modeli AI hazırlık aşamasında önceden hazırlanır.
- Her cümlede downloadModelIfNeeded çağrısı yapılmaz; Translator bellekte tutulur.
- Model indirme 60 saniye, çeviri 15 saniye timeout ile sınırlıdır; sonsuz “Türkçeye çevriliyor…” beklemesi engellenir.
- Önceki ses→Whisper hattına dokunulmamıştır.
