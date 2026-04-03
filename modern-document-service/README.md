# Modern Document Service

> **Java 21 + Spring Boot 3.2** — Legacy Akbank Document Service'in yeniden yazılmış hali.  
> Virtual Threads, HikariCP, PostgreSQL, RSA imzalama, Pagination — tüm legacy sorunları çözüldü.

---

## 🚀 Lokal Çalıştırma

### Gereksinimler
- Java 21 (`brew install openjdk@21`)
- IntelliJ IDEA (Maven bundled gelir)

### 1. Projeyi klonla

```bash
git clone https://github.com/mehmetalitaskan/CoderSpaceBackend202.git
cd CoderSpaceBackend202/modern-document-service
```

### 2. Lokal PostgreSQL DB Kur (Docker ile)

```bash
docker run --name document-db \
  -e POSTGRES_DB=defaultdb \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -p 5432:5432 \
  -d postgres:15-alpine
```

Seed verisini yükle:

```bash
docker exec -i document-db psql -U postgres -d defaultdb < ../legacy-codes/db/seed.sql
```

### 3. Çalıştır

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
DB_URL="jdbc:postgresql://localhost:5432/defaultdb" \
DB_USER="postgres" \
DB_PASS="postgres" \
/Applications/IntelliJ\ IDEA.app/Contents/plugins/maven/lib/maven3/bin/mvn clean spring-boot:run -DskipTests
```

> 🟢 Uygulama `http://localhost:8080` adresinde ayağa kalkar.

### IntelliJ'den çalıştırmak için
1. `File` → `Project Structure` → `SDK`: **Java 21** seç
2. `ModernDocumentServiceApplication` → sağ tık → `Modify Run Configuration`
3. **Environment variables** alanına yapıştır:
```
DB_URL=jdbc:postgresql://localhost:5432/defaultdb;DB_USER=postgres;DB_PASS=postgres
```
4. ▶ **Run**

---

## ☁️ DigitalOcean Kubernetes Deployment

### Gereksinimler
- `kubectl` kurulu
- DigitalOcean kubeconfig (`k8s-1-35-1-do-2-ams3-...-kubeconfig.yaml`)

### 1. Kubeconfig ayarla

```bash
export KUBECONFIG=/path/to/k8s-1-35-1-do-2-ams3-1775133809913-kubeconfig.yaml
kubectl config use-context do-ams3-k8s-1-35-1-do-2-ams3-1775133809913
```

### 2. DB Secret oluştur (ilk kurulumda bir kez)

```bash
kubectl apply -f modern-document-service/k8s/db-secret.yaml
```

> ⚠️ `db-secret.yaml` `.gitignore`'dadır — repo'da bulunmaz. Manuel oluşturulmalıdır:
```bash
kubectl create secret generic modern-db-secret \
  --from-literal=DB_URL="jdbc:postgresql://<host>:25060/defaultdb?sslmode=require" \
  --from-literal=DB_USER="<kullanici>" \
  --from-literal=DB_PASS="<sifre>"
```

### 3. Deploy et

```bash
kubectl apply -f modern-document-service/k8s/
kubectl rollout status deployment/modern-document-service -n default
```

### 4. Servis IP'sini öğren

```bash
kubectl get svc modern-document-service -n default
# EXTERNAL-IP: 178.128.141.84
```

> 🟢 Uygulama `http://178.128.141.84` adresinde çalışır.

---

## 🔄 CI/CD (GitHub Actions)

`modern-document-service/**` altında herhangi bir değişiklik `main`'e push edildiğinde otomatik çalışır:

```
push → Build Docker image → Push to DO Registry → kubectl apply → rollout status
```

Workflow: [`.github/workflows/deploy-modern.yml`](../.github/workflows/deploy-modern.yml)

---

## 📡 API Endpoints

| Method | URL | Açıklama |
|--------|-----|----------|
| `GET` | `/api/templates` | Tüm şablonları listele |
| `POST` | `/api/documents/sign` | Belge imzala |
| `GET` | `/api/documents/signed?page=0&size=20` | İmzalı belgeler (sayfalı) |
| `GET` | `/api/documents/signed/{id}` | ID ile belge getir |
| `GET` | `/api/documents/pdf/{id}` | PDF olarak indir |
| `GET` | `/api/health` | Servis sağlık durumu |
| `GET` | `/api/status` | Virtual Thread + çözülen sorunlar |
| `GET` | `/actuator/health` | Spring Actuator health |

### Örnek İstek — Belge İmzala

```bash
curl -X POST http://localhost:8080/api/documents/sign \
  -H "Content-Type: application/json" \
  -d '{
    "templateId": 1,
    "customerId": "12345678901",
    "customerName": "Ahmet Yılmaz"
  }'
```

```json
{
  "id": 6,
  "templateId": 1,
  "customerId": "12345678901",
  "customerName": "Ahmet Yılmaz",
  "signatureHash": "a3f9c2...",
  "signedAt": "2026-04-03T08:30:27Z",
  "documentUrl": "http://localhost:8080/api/documents/pdf/6"
}
```

---

## ⚡ Legacy vs Modern

| Sorun | Legacy | Modern |
|-------|--------|--------|
| #1 Thread limiti | `newFixedThreadPool(10)` | Virtual Threads — sınırsız |
| #2 Blocking I/O | `Thread.sleep(3000)` | Direkt JPA çağrısı |
| #3 OOM riski | `new byte[512KB]` per request | Yok |
| #4 CPU israfı | Her request'te RSA üretimi | `@Bean` singleton KeyPair |
| #5 Connection pool yok | `DriverManager.getConnection()` | HikariCP (max 20) |
| #6 In-memory state | `HashMap` — restart'ta kaybolur | PostgreSQL |
| #7 Pagination yok | `SELECT *` tüm kayıtlar | `Page<T>` + `Pageable` |
