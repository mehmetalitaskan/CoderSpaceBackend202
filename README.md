# CoderSpaceBackend202
## "The Legacy Bottleneck" — Akbank Document Service Modernizasyonu

Bu repo, **Java 8 ile yazılmış legacy bir bankacılık servisinin** nasıl modern bir mimariye taşındığını göstermektedir.

---

## 🔴 Legacy Servis — 7 Kritik Sorun

| # | Sorun | Kod | Etki |
|---|-------|-----|------|
| 1 | **Max 10 Thread** | `newFixedThreadPool(10)` | 11. istek kuyruğa girer, sistem felç olur |
| 2 | **Blocking I/O** | `Thread.sleep(3000)` | Her istek 3 sn bekler, thread boşa harcanır |
| 3 | **OOM Riski** | `new byte[512KB]` per request | GC baskısı artar, pod çöker |
| 4 | **CPU İsrafı** | Her istekte RSA KeyPair üretimi | 2048-bit üretim son derece maliyetli |
| 5 | **Connection Pool Yok** | `DriverManager.getConnection()` | Her sorguda yeni bağlantı, gecikme + limit |
| 6 | **In-Memory State** | `HashMap<Integer, String>` | Pod restart'ta tüm veriler kaybolur |
| 7 | **Pagination Yok** | `SELECT *` tüm kayıtlar | Binlerce kayıtta OOM kaçınılmaz |

---

## 🟢 Modern Servis — Çözümler

| # | Çözüm | Teknoloji |
|---|-------|-----------|
| 1 | **Virtual Threads** | Java 21 Project Loom — sınırsız thread |
| 2 | **Non-blocking** | Direkt JPA çağrısı, sleep yok |
| 3 | **Streaming** | 512KB buffer tamamen kaldırıldı |
| 4 | **Singleton KeyPair** | `@Bean` — uygulama başında bir kez üretilir |
| 5 | **HikariCP** | Spring Boot default pool (max 20) |
| 6 | **PostgreSQL** | Tüm veriler kalıcı, pod restart'ta kaybolmaz |
| 7 | **Pagination** | `Page<T>` + `Pageable` — `?page=0&size=20` |

---

## 📁 Repo Yapısı

```
├── legacy-codes/                  ← Java 8 legacy servis (sorunları görmek için)
│   ├── LegacyDocumentService.java
│   ├── db/seed.sql                ← Veritabanı seed verisi
│   ├── k8s/                       ← Legacy Kubernetes manifestleri
│   └── Legacy-Document-Service.postman_collection.json
│
└── modern-document-service/       ← Spring Boot 3.2 + Java 21
    ├── src/
    ├── k8s/                       ← Modern Kubernetes manifestleri
    └── Modern-Document-Service.postman_collection.json
```

---

## 🧪 Postman ile Test

### 1. Collection'ı İçe Aktar

- **Legacy:** `legacy-codes/Legacy-Document-Service.postman_collection.json`
- **Modern:** `modern-document-service/Modern-Document-Service.postman_collection.json`

Postman → **Import** → dosyayı sürükle bırak.

### 2. Environment Değişkeni Ayarla

Postman'de yeni bir Environment oluştur:

| Variable | Legacy Değeri | Modern Değeri |
|----------|--------------|---------------|
| `base_url` | `http://188.166.202.183` | `http://178.128.141.84` |

> Lokal test için: `http://localhost:8080`

### 3. Test Senaryoları & Beklenen Sonuçlar

#### 🔵 GET `/api/templates` — Şablonları Listele
```json
[
  { "id": 1, "name": "Bireysel Kredi Sozlesmesi", "version": "v3.2" },
  { "id": 2, "name": "Konut Kredisi Sozlesmesi",  "version": "v2.1" }
]
```

#### 🔵 POST `/api/documents/sign` — Belge İmzala
```json
// Request Body:
{
  "templateId": 1,
  "customerId": "12345678901",
  "customerName": "Ahmet Yılmaz"
}

// Modern Response (hızlı ~50ms, documentUrl var):
{
  "id": 6,
  "templateId": 1,
  "customerId": "12345678901",
  "customerName": "Ahmet Yılmaz",
  "signatureHash": "a3f9c2d1...",
  "signedAt": "2026-04-03T08:30:27Z",
  "documentUrl": "http://178.128.141.84/api/documents/pdf/6"
}

// Legacy Response (~2000ms, Thread.sleep(3000) nedeniyle yavaş):
{
  "documentId": 6,
  "status": "SIGNED",
  "documentUrl": "http://188.166.202.183/api/documents/pdf?id=6"
}
```

#### 🔵 GET `/api/documents/signed?page=0&size=5` — Sayfalı Liste (FIX #7)
```json
// Modern (pagination var):
{
  "content": [...],
  "page": 0,
  "size": 5,
  "totalElements": 12,
  "totalPages": 3
}
```

#### 🔵 GET `/api/documents/pdf/1` — PDF İndir
> Tarayıcıdan veya Postman'den açıldığında `application/pdf` döner, doğrudan görüntülenebilir.

#### 🔵 GET `/api/status` — Virtual Thread Kanıtı (FIX #1)
```json
{
  "service": "modern-document-service",
  "activeThread": {
    "name": "virtual-...",
    "isVirtual": true        ← Virtual Thread çalışıyor
  },
  "fixedIssues": [
    "FIX #1 — Virtual Threads: MAX_THREADS=10 sınırı kaldırıldı",
    "FIX #2 — Blocking I/O: Thread.sleep(3000) elimine edildi",
    ...
  ]
}
```

### 4. Performans Karşılaştırması

Postman **Collection Runner** ile 20 paralel istek gönder:

| | Legacy | Modern |
|-|--------|--------|
| Yanıt süresi | ~2000ms | ~50-500ms |
| 11+ eş zamanlı istek | ❌ Kuyrukta bekler | ✅ Hepsi işlenir |
| Veri kalıcılığı | ❌ Restart'ta kaybolur | ✅ PostgreSQL'de kalır |
| PDF URL | ✅ Var | ✅ Var |
| Pagination | ❌ Yok | ✅ Var |

---

## 🌐 Canlı Adresler

| Servis | URL |
|--------|-----|
| **Legacy** | http://188.166.202.183 |
| **Modern** | http://178.128.141.84 |

---

## 📖 Detaylı Kurulum

- [Modern Servis README](modern-document-service/README.md) — lokal çalıştırma, Kubernetes deployment, CI/CD

