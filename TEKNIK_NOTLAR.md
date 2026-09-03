# TERCÜMAN v0.9.0 Teknik Notlar

- Temel ses yakalama hattı v0.1 ile aynı tutuldu.
- Mikrofon: 16 kHz mono, 4 saniyelik WAV.
- Telefon sesi: 48 kHz mono, 4 saniyelik WAV.
- Whisper: ggml-base.bin.
- ML Kit Language ID + on-device Translation.
- Supertonic native motoru çeviri ve önizleme akışından çıkarıldı.
- Önizleme: Android TextToSpeech, Türkçe.
- Amaç: önce çevirinin kesin çalışmasını doğrulamak; hız optimizasyonu sonraki sürüm.


v0.9.0 ÇEVİRİ STABİLİZASYONU
- İngilizce→Türkçe ML Kit modeli AI hazırlık aşamasında önceden hazırlanır.
- Her cümlede downloadModelIfNeeded çağrısı yapılmaz; Translator bellekte tutulur.
- Model indirme 60 saniye, çeviri 15 saniye timeout ile sınırlıdır; sonsuz “Türkçeye çevriliyor…” beklemesi engellenir.
- Önceki ses→Whisper hattına dokunulmamıştır.
