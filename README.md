# TERCÜMAN v0.3.0

Tamamen ücretsiz, cihaz ağırlıklı canlı çeviri Android projesinin ilk çalışan çekirdeği.

## Bu pakette
- Bölünmüş ekran / yeniden boyutlandırılabilir Android arayüzü.
- Mikrofon dış sesini 2 sn parçalarla yerel Whisper.cpp üzerinden yazıya çevirme.
- Android 10+ AudioPlaybackCapture ile başka uygulamaların izin verdiği medya sesini yakalama.
- ML Kit cihaz-içi çeviri ile otomatik dil algılama -> Türkçe.
- Supertonic 3 + sherpa-onnx cihaz-içi neural TTS.
- 10 ses profili hedefi: 5 kadın + 5 erkek, Türkçe neural seslendirme.
- GitHub Actions ile telefondan APK üretme.
- API anahtarı, abonelik, dakika ücreti yok.

## İlk kullanım
1. APK'yı kur.
2. Mikrofon iznini ver.
3. `AI MODELLERİNİ HAZIRLA` düğmesine bas.
4. Whisper multilingual base modeli ve Supertonic 3 INT8 modeli bir kez indirilir.
5. Sonra `DIŞ SES` veya `TELEFON SESİ` ile çeviriyi başlat.

## Önemli Android sınırı
Telefon içi ses yakalama Android'in MediaProjection/AudioPlaybackCapture kurallarına tabidir. Kaynak uygulama yakalamayı engellerse TERCÜMAN o uygulamanın sesini alamaz. Bu bir uygulama hatası değil Android güvenlik kuralıdır.

## v0.2.0 düzeltmeleri

- Kullanıcı `DIŞ SES` veya `TELEFON SESİ` başlatmadan hiçbir ses parçası çeviri kuyruğuna alınmaz.
- Eski Activity/telefon yakalama servisi kalıntıları yeni oturumda otomatik durdurulur.
- Çeviri kuyruğu tek parçalık tutulur; eski sesler birikip çevirinin dakikalar geriden gelmesine izin verilmez.
- Ses pencereleri 4 sn → 2 sn indirildi; eski parçaların kuyrukta birikmesi engellendi.
- Yeni ses parçası beklerken eski Türkçe TTS çıktısının sıraya girmesi engellendi.
- Mikrofon moduna Android Acoustic Echo Canceler desteği eklendi; telefon içi modda TERCÜMAN kendi TTS sesini yakalamaz.
- Supertonic ses profillerinin kadın/erkek SID eşleşmesi düzeltildi.

## v0.1.0 test hedefi
Bu sürümde öncelik çekirdeği gerçek telefonda doğrulamaktır: model indirme, ARM64 native kütüphaneler, mikrofon, telefon sesi, ML Kit ve Supertonic. Gecikme optimizasyonu, yüzen altyazı, karşılıklı tercüman ve kulaklık miksajı bir sonraki entegrasyon katmanıdır.


## v0.3.0 — Canlı Çeviri Düzeltmeleri

- Supertonic F1-F5 ve M1-M5 gerçek `voice.bin` SID sırasına göre eşlendi.
- TTS, çeviri/STT kuyruğunu artık bloklamaz; yeni çeviri geldiğinde eski ses kesilip en güncel metin seslendirilir.
- Supertonic canlı üretim callback'i ile ilk ses paketi üretim tamamlanmadan oynatılmaya başlanır.
- ML Kit çeviri modelleri dil başına bellekte açık tutulur; her parçada yeniden indirme/kurulum yapılmaz.
- Whisper canlı kullanım için `ggml-tiny-q8_0.bin` modeline geçirildi.
- Ses pencereleri 1.2 saniyeye indirildi ve sessiz parçalar Whisper'a gönderilmez.
- Telefon içi AudioPlaybackCapture için 48 kHz / 44.1 kHz / 16 kHz formatları sırayla denenir ve MediaProjection kapanışı izlenir.
- Telefon içi akış sessiz kalırsa kullanıcıya kaynak uygulamanın yakalamayı engellemesi veya Android cihazındaki playback-capture sorunu olabileceği bildirilir.

### Bilinen Android sınırı

`AudioPlaybackCapture` yalnızca kaynak uygulama yakalamaya izin veriyorsa ve ses kullanımı `MEDIA`, `GAME` veya `UNKNOWN` olduğunda çalışır. Uygulama/DRM/politika nedeniyle sessiz akış alınması Android tarafından engellenebilir. Bu durum TERCÜMAN'ın koduyla zorla aşılamaz.
