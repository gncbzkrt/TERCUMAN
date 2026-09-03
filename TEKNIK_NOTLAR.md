# Teknik notlar / kararlar

- `minSdk 29`: AudioPlaybackCapture Android 10 ile geldi.
- `targetSdk 35`: Android 14+ MediaProjection foreground-service kuralları uygulanır.
- İç ses yakalama bir foreground service içinde yürür.
- Speech-to-text: ücretsiz `whisper-android` AAR + `ggml-base.bin`, 2 saniyelik WAV chunk.
- Translation: ML Kit Translation, Türkçe hedef.
- Neural TTS: sherpa-onnx Supertonic 3 INT8, `lang=tr`, 10 speaker hedefi.
- İlk sürüm arm64-v8a. Modern Android telefonların ana hedefidir.
- Mimari ileride streaming/VAD ile chunk gecikmesini azaltmaya uygundur.


## v0.2.0
- CaptureMode ile açık kullanıcı oturumu olmadan işleme engellendi.
- Channel kapasitesi 1: backlog yerine en yeni ses parçası korunuyor.
- Mic/phone chunk: 2.0 s, overlap yok; tekrar azaltıldı.
- Supertonic SID eşleşmesi: F1..F5=0..4, M1..M5=5..9.


## v0.3.0 Teknik Notları

### Gecikme

Önceki sürümde iki ana gecikme kaynağı vardı: 2 saniyelik sabit pencere ve TTS'nin STT/çeviri kuyruğunu bloklaması. Ayrıca ML Kit Translator her parçada oluşturulup `downloadModelIfNeeded()` çağrılıyordu. v0.3.0'da pencere 1.2 saniyeye indirildi, çeviri modelleri dil başına cache'leniyor ve TTS ayrı latest-wins kuyruğunda çalışıyor.

### Whisper

Canlı kullanım için `ggml-tiny-q8_0.bin` kullanılıyor. Bu seçim doğruluk yerine gecikmeyi önceliklendirir. Base model kullanıcı cihazında varsa v0.3.0 ilk model hazırlamada eski base dosyasını temizler.

### Supertonic ses eşleşmesi

Supertonic 3 `voice.bin` için kullanılan SID sırası `M1..M5, F1..F5` kabul edilir: 0..4 erkek, 5..9 kadın. UI kadınları önce gösterse de SID gerçek model sırasına göre verilir.

### Telefon içi ses

Android `AudioPlaybackCapture` kaynak uygulamanın yakalama politikasına bağlıdır. Yakalanan akış sessizse uygulama bunu artık sessiz pencere olarak algılayıp kullanıcıya durum mesajı gönderir. Android'in kaynak uygulama politikasını aşmak hedeflenmemiştir.
