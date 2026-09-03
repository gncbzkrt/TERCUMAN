# TERCÜMAN v0.6.0

Tamamen ücretsiz, cihaz ağırlıklı canlı çeviri Android uygulaması. API anahtarı, abonelik ve dakika başı ücret yoktur.

## v0.6.0 bu sürümde
- Kadın/erkek Supertonic ses eşleşmesi düzeltildi: F1-F5 ve M1-M5 doğru SID sırasına bağlandı.
- Yeni **Ton** seçimi: Doğal, Enerjik, Sakin, Haberci, Vurgulu.
- Tonlar Supertonic'in desteklediği hız ve kalite/denoising ayarlarıyla doğal ses karakterini koruyacak şekilde uygulanır.
- AI model hazırlama ekranının hemen altında küçük ilerleme göstergesi ve yüzde bulunur: `1/2 Whisper`, `2/2 Doğal Ses`, `AI HAZIR ✓`.
- Sabit 1.2 saniyelik pencere yerine VAD destekli kısa ses parçaları kullanılır; konuşma bittiğinde erken gönderilir, uzun konuşmada yaklaşık 1 saniyelik üst sınır vardır.
- Whisper tiny ile düşük gecikme önceliklendirilir.
- Tek parçalık latest-wins kuyruk korunur; işlem yetişemediğinde eski parça atılır.
- TTS ayrı kuyrukta çalışır ve yeni çeviri geldiğinde eski ses kesilir.
- Telefon içi yakalamada sessizlik durumu açıkça gösterilir.

## İlk kullanım
1. APK'yı kur.
2. Mikrofon iznini ver.
3. `AI MODELLERİNİ HAZIRLA` düğmesine bas.
4. Altındaki küçük yüzde göstergesinden iki modelin hazırlık durumunu izle.
5. `AI HAZIR ✓` görüldüğünde `DIŞ SES` veya `TELEFON SESİ` seç.
6. Sesli kullanım için bir Ses ve Ton seç.

## Tonlar
- **Doğal:** dengeli konuşma
- **Enerjik:** daha canlı ve hızlı ritim
- **Sakin:** daha yavaş ve yumuşak ritim
- **Haberci:** net ve tempolu ritim
- **Vurgulu:** daha kontrollü, belirgin konuşma

Supertonic 3'ün kamuya açık API'sinde ayrı bir emotion/pitch parametresi bulunmadığından bu tonlar doğrudan yapay bir pitch efekti değil, modelin doğal sentezini hız/denoising ayarlarıyla karakterlendiren presetlerdir.

## Telefon içi ses sınırı
Android AudioPlaybackCapture yalnızca kaynak uygulama yakalamaya izin verdiğinde çalışır. YouTube/Instagram gibi bir uygulamada akış `blank/sessiz` kalırsa bu durum TERCÜMAN'ın metin çeviri motorundan bağımsızdır; Android veya kaynak uygulama capture politikasını engelliyor olabilir.

## Gecikme
Bu sürüm gerçek zamanlıya yaklaşmak için VAD tabanlı kısa parçalar kullanır. Kullanılan ücretsiz Whisper Android entegrasyonu doğrudan tam online streaming API sağlamadığı için bu sürüm henüz gerçek token-token streaming değildir. Bir sonraki mimari adım, Türkçe destekli uygun bir online ASR modeli bulunduğunda gerçek streaming ASR'ye geçmektir.

## Dağıtım
GitHub Actions Android SDK 35 + Java 17 ile debug APK üretir.


## v0.6.0 Stabilizasyon

- Ücretsiz Whisper Android AAR ile uyumlu standart `ggml-tiny.bin` kullanılır; v0.4'teki q8 model kaldırıldı.
- Sesli çıktı varsayılan olarak kapalıdır; çeviri zinciri önce doğrulanır.
- Çeviri aşamaları ekranda açıkça gösterilir.
- Supertonic doğal ses, yalnız kullanıcı sesli çıktıyı açtığında devreye girer.
