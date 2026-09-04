# TERCÜMAN v1.0.0 — Streaming deney sürümü

Bu sürüm, v0.x dosya tabanlı Whisper zincirinden ayrılarak sherpa-onnx OnlineRecognizer ile gerçek incremental/streaming ASR dener. Ses 100 ms PCM parçaları halinde tek bir OnlineStream'e beslenir; parçalar ayrı ayrı tanınmaz.

## İlk test

- Kaynak dil: İngilizce
- Hedef: Türkçe
- ASR: sherpa-onnx streaming Zipformer 20M (CPU, int8 encoder/joiner)
- Model yaklaşık 128 MB arşiv olarak ilk kullanımda indirilir.
- Çeviri: cihaz içi Google ML Kit EN→TR
- Mikrofon ve Android AudioPlaybackCapture aynı streaming çekirdeğine girer.
- Android playback capture yalnızca kaynak uygulama yakalamaya izin veriyorsa çalışır.

Bu ilk streaming deneyidir; çok dilli ASR, kayan pencere ve iki yönlü konuşma sonraki aşamadır.
