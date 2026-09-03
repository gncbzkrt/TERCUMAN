# Teknik notlar / kararlar

- `minSdk 29`: AudioPlaybackCapture Android 10 ile geldi.
- `targetSdk 35`: Android 14+ MediaProjection foreground-service kuralları uygulanır.
- İç ses yakalama bir foreground service içinde yürür.
- Speech-to-text: ücretsiz `whisper-android` AAR + `ggml-base.bin`, 4 saniyelik WAV chunk.
- Translation: ML Kit Translation, Türkçe hedef.
- Neural TTS: sherpa-onnx Supertonic 3 INT8, `lang=tr`, 10 speaker hedefi.
- İlk sürüm arm64-v8a. Modern Android telefonların ana hedefidir.
- Mimari ileride streaming/VAD ile chunk gecikmesini azaltmaya uygundur.
