# Akbank — Legacy Document Service

> **"The Legacy Rescue" Sunumu — Adım 1: Problemi Canlı Göster**

## Endpointler

| Method | URL | Açıklama |
|--------|-----|----------|
| `POST` | `/api/documents/process` | Doküman işle (PDF + RSA imza) |
| `GET`  | `/api/health`            | Sağlık durumu |
| `GET`  | `/api/status`            | Metrikler + bilinen sorunlar |

---

## Lokal Çalıştırma

```bash
# Derle
javac LegacyDocumentService.java

# Çalıştır
java LegacyDocumentService
```

## Docker ile Çalıştırma

```bash
docker build -t akbank-legacy-service .
docker run -p 8080:8080 akbank-legacy-service
```

---

## Problemi Canlı Göstermek İçin

### 15 paralel istek gönder — thread havuzunu patlatmak için:
```bash
for i in $(seq 1 15); do
  curl -s -X POST http://localhost:8080/api/documents/process \
    -d "Akbank-Musteri-$i" &
done
wait
```

### Sağlık durumunu kontrol et:
```bash
curl http://localhost:8080/api/health
curl http://localhost:8080/api/status
```

---

## Gözlemlenen Sorunlar

1. **Thread havuzu dolunca** — 11. istek ve sonrası kuyrukta bekler
2. **Her istek ~3 sn bloklar** — terminalde `Thread.sleep` gecikmesini göreceksiniz
3. **OOM riski** — `-Xmx256m` ile sınırlandırıldı, yüksek trafikte pod çöker
4. **CPU israfı** — her istekte 2048-bit RSA KeyPair sıfırdan üretiliyor



