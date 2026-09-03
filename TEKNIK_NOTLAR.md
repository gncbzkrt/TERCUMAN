# TERCÜMAN v0.6.0 Teknik Notları

- `minSdk 29`: Android 10+ AudioPlaybackCapture.
- `targetSdk 35`.
- İç ses yakalama foreground service + MediaProjection.
- STT: `dev.ffmpegkit-maintained:whisper-android:1.0.0` + `ggml-tiny-q8_0.bin`.
- Translation: ML Kit Translation, hedef Türkçe.
- TTS: sherpa-onnx Supertonic 3 INT8, 10 hazır ses.

## Ses eşleşmesi
Supertonic 3 dokümantasyonunda sesler M1-M5 ve F1-F5 olarak sunulur. Sherpa paketindeki `voice.bin` indekslemesi bu uygulamada F1-F5 = SID 0-4, M1-M5 = SID 5-9 olarak kullanılır. v0.3'teki ters varsayım kaldırıldı.

## Ton sistemi
`ToneProfile` hız çarpanı + denoising step preset'i taşır. Public Supertonic API'sinde bağımsız emotion/pitch parametresi olmadığı için tonlar doğal sentezi bozmadan ritim/kalite karakteri verir.

## AI hazırlık göstergesi
UI altında yatay küçük progress bar bulunur. Whisper aşaması toplamın ilk %25'i, Supertonic aşaması kalan %75'i temsil eder. Metin aynı anda `1/2`, `2/2` ve sonunda `AI HAZIR ✓` olarak görünür.

## Düşük gecikme
Mikrofon ve playback capture artık 20 ms frame + basit VAD kullanır. Konuşma yaklaşık 260 ms sessiz kaldığında parça gönderilir; kesintisiz konuşmada yaklaşık 1 saniyelik üst sınır vardır. Tek parçalık latest-wins kuyruk sayesinde işlemci yetişemediğinde eski parçalar atılır.

Bu hâlâ dosya-parçası tabanlı Whisper çağrısıdır; tam online/token streaming değildir. Türkçe için uygun ve cihazlarda pratik çalışan bir online ASR modeli seçildiğinde streaming katmanı ayrıca eklenebilir.

## Telefon içi ses
AudioPlaybackCapture kaynak uygulamanın capture policy'sine tabidir. Sessiz akışta UI bunu kullanıcıya bildirir. TERCÜMAN'ın kendi TTS çıkışı `ALLOW_CAPTURE_BY_NONE` ile yakalamadan hariç tutulur.
