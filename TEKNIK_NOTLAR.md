# TERCÜMAN v1.0.1.1 teknik notları

## Neden streaming?
v0.x sürümlerinde 1–4 saniyelik WAV parçaları Whisper'a ayrı ayrı gönderiliyordu. Bu hem gecikme hem de kısa parçaların yanlış tanınması sorununa yol açtı.

v1.0.1 tek bir sherpa-onnx OnlineStream kullanıyor. OnlineRecognizer ses geldikçe decode edebiliyor ve partial sonuç döndürüyor.

## Model
İlk deney için küçük İngilizce `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17` seçildi. Resmi sherpa belgeleri bu modeli küçük ve yalnızca İngilizce olarak tanımlıyor. Bu nedenle v1.0.1 kaynak dili İngilizce ile sınırlandırıldı.

## Ses kaynakları
- Mikrofon: 16 kHz mono, 100 ms PCM chunks.
- Telefon sesi: Android AudioPlaybackCapture ile 48 kHz mono, 100 ms chunks; sherpa girdisi gerektiğinde kendi resampling yolunu kullanır.
- Her iki kaynak aynı `StreamingHub` üzerinden aynı ASR stream'ine gider.

## Çeviri
Partial İngilizce metin yaklaşık 650 ms aralıklarla ML Kit EN→TR'ye gönderilir. Sonuç değiştikçe Türkçe alanı güncellenir. Endpoint oluşunca son çeviri hemen yapılır.

## Sınırlar
Android playback capture kaynak uygulamanın capture politikasına ve MediaProjection iznine bağlıdır. Bazı uygulamalar/DRM içerikler yakalanamaz.
