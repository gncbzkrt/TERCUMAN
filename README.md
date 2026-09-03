# TERCÜMAN v0.1.0

Tamamen ücretsiz, cihaz ağırlıklı canlı çeviri Android projesinin ilk çalışan çekirdeği.

## Bu pakette
- Bölünmüş ekran / yeniden boyutlandırılabilir Android arayüzü.
- Mikrofon dış sesini 4 sn parçalarla yerel Whisper.cpp üzerinden yazıya çevirme.
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

## v0.1.0 test hedefi
Bu sürümde öncelik çekirdeği gerçek telefonda doğrulamaktır: model indirme, ARM64 native kütüphaneler, mikrofon, telefon sesi, ML Kit ve Supertonic. Gecikme optimizasyonu, yüzen altyazı, karşılıklı tercüman ve kulaklık miksajı bir sonraki entegrasyon katmanıdır.
