# GitHub Copilot — Architect-Level Instructions
# Modern Document Service | Java 21 + Spring Boot 3.2

> **Senaryo:** "The Legacy Bottleneck" — Akbank Legacy Document Service'in tüm kritik sorunları  
> bu projede çözülüyor. Her satır kod, bir legacy anti-pattern'e verilen bilinçli bir cevaptır.

---

## 🏛️ Proje Kimliği

| Alan | Değer |
|------|-------|
| `groupId` | `com.coderspace` |
| `artifactId` | `modern-document-service` |
| `base package` | `com.coderspace.documentservice` |
| `Java` | `21` (Virtual Threads — Project Loom) |
| `Spring Boot` | `3.2.x` |
| `DB` | `PostgreSQL` + `HikariCP` (connection pool) |
| `Build` | `Maven` |

---

## 📦 Kesin Paket Yapısı (Değiştirme)

```
com.coderspace.documentservice
├── ModernDocumentServiceApplication.java   ← @SpringBootApplication
│
├── config/
│   ├── AppConfig.java                      ← @Bean RSA KeyPair singleton, Virtual Thread Executor
│   └── VirtualThreadConfig.java            ← Tomcat Virtual Thread executor config
│
├── domain/
│   ├── entity/
│   │   ├── DocumentTemplate.java           ← @Entity, @Table("document_templates")
│   │   └── SignedDocument.java             ← @Entity, @Table("signed_documents")
│   └── repository/
│       ├── DocumentTemplateRepository.java ← JpaRepository + custom query
│       └── SignedDocumentRepository.java   ← JpaRepository + Page<SignedDocument>
│
├── application/
│   ├── dto/
│   │   ├── request/
│   │   │   └── SignDocumentRequest.java    ← Java record + Bean Validation
│   │   └── response/
│   │       ├── DocumentTemplateResponse.java  ← Java record
│   │       ├── SignedDocumentResponse.java    ← Java record
│   │       └── PagedResponse.java             ← Generic Java record<T>
│   ├── service/
│   │   └── DocumentService.java            ← interface
│   └── exception/
│       ├── DocumentNotFoundException.java  ← extends RuntimeException
│       ├── TemplateNotFoundException.java  ← extends RuntimeException
│       └── SignatureProcessingException.java ← extends RuntimeException
│
├── infrastructure/
│   └── service/
│       └── DocumentServiceImpl.java        ← implements DocumentService
│
└── presentation/
    ├── controller/
    │   └── DocumentController.java         ← @RestController, @RequestMapping("/api")
    └── exception/
        └── GlobalExceptionHandler.java     ← @RestControllerAdvice
```

---

## ⚙️ Teknoloji Kararları & Legacy → Modern Mapping

| Legacy Sorun | Sorunun Kodu | Modern Çözüm |
|---|---|---|
| #1 Max 10 thread | `Executors.newFixedThreadPool(10)` | `Virtual Threads` — milyonlarca eş zamanlı thread |
| #2 Blocking I/O | `Thread.sleep(3000)` | Non-blocking JPA çağrıları, Virtual Threads bloklamayı mount eder |
| #3 OOM Riski | `new byte[512 * 1024]` per request | Elimine edildi, streaming yaklaşım |
| #4 CPU israfı | `KeyPairGenerator` her request'te | `@Bean` singleton `KeyPair` — uygulama başında bir kez üretilir |
| #5 No pool | `DriverManager.getConnection()` | `HikariCP` — Spring Boot default connection pool |
| #6 In-memory | `HashMap<Integer, String>` | Sadece PostgreSQL — no in-memory state |
| #7 No pagination | `SELECT *` tüm kayıtlar | `Page<T>` + `Pageable` — `?page=0&size=20` |

---

## 🔒 Kodlama Standartları (Bunlara kesinlikle uy)

### 1. Exception Hiyerarşisi
```java
// DOĞRU — Checked exception değil, Runtime kullan
public class DocumentNotFoundException extends RuntimeException {
    private final Long documentId;

    public DocumentNotFoundException(Long documentId) {
        super("Signed document not found with id: " + documentId);
        this.documentId = documentId;
    }

    public Long getDocumentId() { return documentId; }
}

// GlobalExceptionHandler'da yakalanır — controller'da try/catch YOK
```

### 2. GlobalExceptionHandler Yapısı
```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    // Her custom exception için ayrı @ExceptionHandler
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(
            DocumentNotFoundException ex, HttpServletRequest request) {

        ErrorResponse error = ErrorResponse.builder()
            .timestamp(Instant.now())
            .status(HttpStatus.NOT_FOUND.value())
            .error("Document Not Found")
            .message(ex.getMessage())
            .path(request.getRequestURI())
            .build();

        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error);
    }

    // Bean Validation hataları için
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(...) { }

    // Genel fallback
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(...) { }
}
```

### 3. ErrorResponse — Standart Hata Formatı
```java
// Java record olarak tanımla — Lombok @Builder ile değil
public record ErrorResponse(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path
) {}
```

### 4. Java Records — DTO'lar için
```java
// Request DTO — Bean Validation annotations eklenir
public record SignDocumentRequest(
    @NotNull @Positive Integer templateId,
    @NotBlank @Size(min = 11, max = 11) String customerId,
    @NotBlank @Size(min = 2, max = 100) String customerName
) {}

// Response DTO — immutable, serileştirme kolay
public record SignedDocumentResponse(
    Long id,
    Integer templateId,
    String customerId,
    String customerName,
    String signatureHash,
    Instant signedAt
) {}
```

### 5. Entity Standartı
```java
@Entity
@Table(name = "document_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA için protected no-arg
public class DocumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    // Factory method — new ile dışarıdan oluşturma
    public static DocumentTemplate create(String name, String description) {
        DocumentTemplate t = new DocumentTemplate();
        t.name = name;
        t.description = description;
        return t;
    }
}
```

### 6. Service Katmanı Kuralları
```java
@Service
@Transactional(readOnly = true)  // Sınıf seviyesinde read-only default
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentTemplateRepository templateRepo;
    private final SignedDocumentRepository signedDocRepo;
    private final KeyPair rsaKeyPair;  // Inject singleton

    @Override
    public List<DocumentTemplateResponse> findAllTemplates() {
        return templateRepo.findAll().stream()
            .map(this::toTemplateResponse)
            .toList();  // Java 16+ — Collectors.toList() değil
    }

    @Override
    @Transactional  // Write işlemleri override eder
    public SignedDocumentResponse signDocument(SignDocumentRequest request) {
        DocumentTemplate template = templateRepo.findById(request.templateId())
            .orElseThrow(() -> new TemplateNotFoundException(request.templateId()));
        // ...
    }
}
```

### 7. Controller Katmanı Kuralları
```java
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class DocumentController {

    private final DocumentService documentService;

    // Controller'da try/catch YOK — GlobalExceptionHandler halleder
    // İş mantığı YOK — sadece HTTP ↔ service köprüsü
    // @Valid ile validasyon tetiklenir

    @GetMapping("/templates")
    public ResponseEntity<List<DocumentTemplateResponse>> getTemplates() {
        return ResponseEntity.ok(documentService.findAllTemplates());
    }

    @PostMapping("/documents/sign")
    public ResponseEntity<SignedDocumentResponse> signDocument(
            @Valid @RequestBody SignDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(documentService.signDocument(request));
    }

    @GetMapping("/documents/signed")
    public ResponseEntity<PagedResponse<SignedDocumentResponse>> getSignedDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(documentService.findSignedDocuments(page, size));
    }
}
```

---

## 🚀 Virtual Threads Konfigürasyonu

```java
// VirtualThreadConfig.java
@Configuration
public class VirtualThreadConfig {

    // FIX #1 — Tomcat'i Virtual Thread executor ile çalıştır
    // Max thread sınırı YOK — JVM yönetir (milyonlarca sanal thread)
    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadCustomizer() {
        return protocolHandler ->
            protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}

// AppConfig.java
@Configuration
public class AppConfig {

    // FIX #4 — RSA KeyPair uygulama başında BİR KEZ üretilir
    @Bean
    public KeyPair rsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }
}
```

---

## 🗄️ application.yml Referansı

```yaml
spring:
  application:
    name: modern-document-service
  
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/defaultdb}
    username: ${DB_USER:postgres}
    password: ${DB_PASS:}
    # FIX #5 — HikariCP (Spring Boot default)
    hikari:
      maximum-pool-size: 20
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
  
  jpa:
    hibernate:
      ddl-auto: validate        # Production'da validate — schema DB'den gelir
    show-sql: false
    properties:
      hibernate:
        format_sql: false
        dialect: org.hibernate.dialect.PostgreSQLDialect

  # FIX #1 — Virtual Threads (Spring Boot 3.2+)
  threads:
    virtual:
      enabled: true

server:
  port: 8080

management:
  endpoints:
    web:
      exposure:
        include: health, info, metrics, prometheus
  endpoint:
    health:
      show-details: always
```

---

## ☸️ Kubernetes Manifest Referansı

### deployment.yaml — Modern Service
```yaml
# FIX #1 — Virtual Threads sayesinde tek pod çok daha fazla isteği kaldırır
# HPA ile yatay ölçekleme de eklendi
resources:
  requests:
    memory: "256Mi"
    cpu: "250m"
  limits:
    memory: "512Mi"
    cpu: "1000m"
```

### hpa.yaml — Horizontal Pod Autoscaler
```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: modern-document-service-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: modern-document-service
  minReplicas: 2
  maxReplicas: 10
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: 80
```

---



--
### PROMPT 1 — Entity Katmanı
```
Aşağıdaki kurallara göre iki JPA Entity sınıfı oluştur:
Proje: com.coderspace.documentservice
Hedef paket: com.coderspace.documentservice.domain.entity
Kurallar:
- Lombok @Getter, @NoArgsConstructor(access = PROTECTED) kullan
- Dışarıdan new ile oluşturmayı engelle, static factory method ekle
- @Column kısıtlarını (nullable, length) ekle
- @CreationTimestamp ile audit field ekle
1. DocumentTemplate entity:
   - Tablo: document_templates
   - Alanlar: id (Long, PK, auto), name (String, 100), description (String, 500), 
     version (String, 20), content (TEXT), createdAt (Instant)
2. SignedDocument entity:
   - Tablo: signed_documents  
   - Alanlar: id (Long, PK, auto), templateId (Integer), customerId (String, 20),
     customerName (String, 100), signatureHash (String, 128), signedAt (Instant, @CreationTimestamp)
   - DocumentTemplate ile @ManyToOne JOIN yok — sadece templateId integer tut (loose coupling)
```
---
### PROMPT 2 — Repository Katmanı
```
Aşağıdaki kurallara göre iki Spring Data JPA Repository interface'i oluştur:
Proje: com.coderspace.documentservice
Hedef paket: com.coderspace.documentservice.domain.repository
1. DocumentTemplateRepository:
   - JpaRepository<DocumentTemplate, Long> extend et
   - findByName(String name): Optional<DocumentTemplate>
   - existsByNameAndVersion(String name, String version): boolean
2. SignedDocumentRepository:
   - JpaRepository<SignedDocument, Long> extend et
   - findAllByCustomerId(String customerId, Pageable pageable): Page<SignedDocument>
   - countByCustomerId(String customerId): long
   - FIX #7: Tüm kayıtları çekecek metodlar Page<> döndürmeli, List<> değil
```
---
### PROMPT 3 — DTO + Exception Katmanı
```
Aşağıdaki kurallara göre DTO'ları ve Exception sınıflarını oluştur:
Proje: com.coderspace.documentservice
1. Request DTO — paket: application.dto.request
   SignDocumentRequest Java record:
   - templateId: @NotNull @Positive Integer
   - customerId: @NotBlank @Size(min=11, max=11) String
   - customerName: @NotBlank @Size(min=2, max=100) String
2. Response DTO'lar — paket: application.dto.response
   - DocumentTemplateResponse record: id, name, description, version
   - SignedDocumentResponse record: id, templateId, customerId, customerName, signatureHash, signedAt (Instant)
   - PagedResponse<T> generic record: content (List<T>), page, size, totalElements, totalPages
3. ErrorResponse record — paket: presentation.exception:
   - timestamp (Instant), status (int), error (String), message (String), path (String)
4. Exception sınıfları — paket: application.exception:
   - DocumentNotFoundException(Long id) extends RuntimeException
   - TemplateNotFoundException(Integer templateId) extends RuntimeException
   - SignatureProcessingException(String reason, Throwable cause) extends RuntimeException
   Her birinde anlamlı super("...") mesajı olsun.
```
---
### PROMPT 4 — Config Katmanı
```
Aşağıdaki iki @Configuration sınıfını oluştur:
Proje: com.coderspace.documentservice
Hedef paket: com.coderspace.documentservice.config
1. AppConfig.java:
   - @Bean KeyPair rsaKeyPair(): RSA 2048-bit, uygulama başında BİR KEZ üretilir
     throws NoSuchAlgorithmException — Spring boot exception olarak fırlatır
   - @Bean @Primary Executor virtualThreadExecutor(): Executors.newVirtualThreadPerTaskExecutor()
   - Log ata: "RSA KeyPair initialized (singleton)" mesajı @PostConstruct'ta değil, bean metodunda
2. VirtualThreadConfig.java:
   - FIX #1: TomcatProtocolHandlerCustomizer @Bean
   - Tomcat executor'ı Executors.newVirtualThreadPerTaskExecutor() ile değiştir
   - Javadoc: Legacy'deki MAX_THREADS=10 sınırı burada tamamen ortadan kalkıyor
```
---
### PROMPT 5 — Service Katmanı
```
Aşağıdaki kurallara göre DocumentService interface ve DocumentServiceImpl sınıfını oluştur:
Proje: com.coderspace.documentservice
Interface paketi: application.service
Impl paketi: infrastructure.service
DocumentService interface metodları:
- List<DocumentTemplateResponse> findAllTemplates()
- SignedDocumentResponse signDocument(SignDocumentRequest request)
- Page<SignedDocumentResponse> findSignedDocuments(int page, int size)
- SignedDocumentResponse findSignedDocumentById(Long id)
DocumentServiceImpl kuralları:
- @Service @Transactional(readOnly=true) sınıf seviyesinde
- @Transactional override ile signDocument metodu
- @RequiredArgsConstructor ile injection (field injection değil)
- KeyPair rsaKeyPair bean'i inject et (FIX #4 - singleton)
- signDocument içinde:
  1. Template'i bul, yoksa TemplateNotFoundException fırlat
  2. Template content'i customerName/customerId ile replace et
  3. Singleton KeyPair ile SHA256withRSA imzala
  4. Signature bytes'ı HEX string'e çevir (ilk 64 karakter hash olarak sakla)
  5. SignedDocument oluştur ve kaydet
  6. SignedDocumentResponse döndür
- findSignedDocumentById: yoksa DocumentNotFoundException fırlat
- Stream API + .toList() kullan (Collectors.toList() değil)
- try/catch YOK — SignatureException → SignatureProcessingException olarak wrap et
```
---
### PROMPT 6 — Controller + GlobalExceptionHandler
```
Aşağıdaki kurallara göre REST Controller ve GlobalExceptionHandler oluştur:
Proje: com.coderspace.documentservice
1. DocumentController — paket: presentation.controller
   @RestController @RequestMapping("/api") @RequiredArgsConstructor @Validated
   
   Endpoint'ler:
   - GET  /api/templates → 200 List<DocumentTemplateResponse>
   - POST /api/documents/sign → 201 SignedDocumentResponse (@Valid @RequestBody)
   - GET  /api/documents/signed → 200 PagedResponse (@RequestParam page=0, size=20)
   - GET  /api/documents/signed/{id} → 200 SignedDocumentResponse
   - GET  /api/health → 200 {"status":"UP","service":"modern-document-service","version":"2.0.0-MODERN"}
   - GET  /api/status → 200 (aktif thread bilgisi, service adı, çözülen sorunlar listesi)
   
   Kurallar:
   - Controller'da try/catch OLMAYACAK
   - İş mantığı OLMAYACAK — sadece HTTP ↔ Service köprüsü
   - Her metod ResponseEntity<> döndürecek
2. GlobalExceptionHandler — paket: presentation.exception
   @RestControllerAdvice
   
   Handler'lar:
   - DocumentNotFoundException → 404 NOT_FOUND
   - TemplateNotFoundException → 404 NOT_FOUND
   - SignatureProcessingException → 500 INTERNAL_SERVER_ERROR
   - MethodArgumentNotValidException → 400 BAD_REQUEST (field bazlı hata mesajları)
   - ConstraintViolationException → 400 BAD_REQUEST
   - Exception (fallback) → 500 INTERNAL_SERVER_ERROR
   
   Her handler ErrorResponse record ile tutarlı format döndürmeli.
   Her exception log'lanmalı (Slf4j @Slf4j ile).
```
---
### PROMPT 7 — Kubernetes Manifests
```
Aşağıdaki Kubernetes YAML dosyalarını oluştur:
Servis: modern-document-service
Namespace: default
Image placeholder: IMAGE_PLACEHOLDER
1. deployment.yaml:
   - replicas: 2 (HA — single point of failure yok)
   - RollingUpdate strategy (maxSurge: 1, maxUnavailable: 0)
   - resources: requests(memory:256Mi, cpu:250m) limits(memory:512Mi, cpu:1000m)
   - env: DB_URL, DB_USER, DB_PASS (SecretKeyRef — secret adı: modern-db-secret)
   - livenessProbe: GET /actuator/health, initialDelay:30s, period:10s
   - readinessProbe: GET /actuator/health/readiness, initialDelay:20s, period:5s
   - label: app=modern-document-service
2. service.yaml:
   - ClusterIP type
   - port 80 → targetPort 8080
   - selector: app=modern-document-service
3. hpa.yaml:
   - apiVersion: autoscaling/v2
   - minReplicas: 2, maxReplicas: 10
   - CPU target: 70% utilization
   - Memory target: 80% utilization
   - scaleDown stabilizationWindowSeconds: 300 (ani scale-down önlemi)
4. db-secret.yaml:
   - Secret name: modern-db-secret
   - Keys: DB_URL, DB_USER, DB_PASS (base64 placeholder)
```
---
### PROMPT 8 — Dockerfile (Java 21)
```
Aşağıdaki kurallara göre multi-stage Dockerfile oluştur:
Proje: Maven, Spring Boot 3.2, Java 21
Jar adı: modern-document-service-1.0.0-MODERN.jar
Stage 1 — builder:
- FROM eclipse-temurin:21-jdk-alpine AS builder
- Maven wrapper ile build et (mvn package -DskipTests)
- .mvn/ ve mvnw kopyala
Stage 2 — runtime:
- FROM eclipse-temurin:21-jre-alpine
- Non-root user oluştur: addgroup/adduser appuser
- JVM flags: -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0
- Virtual Threads için: --enable-preview GEREKMİYOR (Java 21 GA)
- EXPOSE 8080
- HEALTHCHECK --interval=30s CMD wget -qO- http://localhost:8080/actuator/health
- USER appuser ile çalıştır (security best practice)
```
---
### PROMPT 9 — Main Application
```
ModernDocumentServiceApplication.java oluştur:
Paket: com.coderspace.documentservice
@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.coderspace.documentservice.domain.repository")
@EntityScan(basePackages = "com.coderspace.documentservice.domain.entity")
main metodunda başlangıç logu:
- "=== Modern Document Service Started ==="
- "=== Virtual Threads: ENABLED ==="
- "=== Legacy Bottleneck: RESOLVED ==="
```
---
### PROMPT 10 — Unit & Integration Tests (JUnit 5 + Mockito)
```
Aşağıdaki kurallara göre test sınıflarını oluştur:
Proje: com.coderspace.documentservice
Test paketi: src/test/java altında production paketiyle birebir aynı yapı
Kullanılacak araçlar:
- JUnit 5 (@ExtendWith(MockitoExtension.class))
- Mockito (@Mock, @InjectMocks, @Captor)
- Spring Boot Test (@SpringBootTest, @WebMvcTest, @DataJpaTest)
- AssertJ (assertThat — Hamcrest değil)
─────────────────────────────────────────────
1. DocumentServiceImplTest — paket: infrastructure.service
   @ExtendWith(MockitoExtension.class)
   
   Mock'lar:
   - DocumentTemplateRepository templateRepo
   - SignedDocumentRepository signedDocRepo
   - KeyPair rsaKeyPair (gerçek RSA 2048 üret — @BeforeAll static)
   
   Test metodları:
   
   a) findAllTemplates_ShouldReturnMappedList()
      - templateRepo.findAll() → 2 dummy DocumentTemplate döndür
      - Sonuç: List<DocumentTemplateResponse> size=2
      - assertThat(result).hasSize(2)
   
   b) signDocument_WhenTemplateExists_ShouldReturnResponse()
      - templateRepo.findById(1) → Optional.of(dummyTemplate)
      - signedDocRepo.save(any()) → saved entity döndür
      - result.customerId() == request.customerId() assert et
      - signatureHash null veya blank olmadığını doğrula
   
   c) signDocument_WhenTemplateNotFound_ShouldThrowTemplateNotFoundException()
      - templateRepo.findById(99) → Optional.empty()
      - assertThatThrownBy(() -> service.signDocument(request))
            .isInstanceOf(TemplateNotFoundException.class)
   
   d) findSignedDocumentById_WhenNotFound_ShouldThrowDocumentNotFoundException()
      - signedDocRepo.findById(999L) → Optional.empty()
      - assertThatThrownBy(...)
            .isInstanceOf(DocumentNotFoundException.class)
            .hasMessageContaining("999")
   
   e) findSignedDocuments_ShouldReturnPagedResponse()
      - signedDocRepo.findAll(any(Pageable.class)) → Page<SignedDocument> döndür
      - result.totalElements() > 0 assert et
─────────────────────────────────────────────
2. DocumentControllerTest — paket: presentation.controller
   @WebMvcTest(DocumentController.class)
   @MockBean DocumentService documentService
   
   Test metodları:
   
   a) getTemplates_ShouldReturn200()
      - documentService.findAllTemplates() → List.of(dummyResponse) stub
      - mockMvc.perform(get("/api/templates"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].name").isNotEmpty())
   
   b) signDocument_WithValidRequest_ShouldReturn201()
      - Geçerli JSON body ile POST /api/documents/sign
      - andExpect(status().isCreated())
      - andExpect(jsonPath("$.signatureHash").isNotEmpty())
   
   c) signDocument_WithInvalidCustomerId_ShouldReturn400()
      - customerId: "123" (11 karakter değil)
      - andExpect(status().isBadRequest())
      - andExpect(jsonPath("$.status").value(400))
   
   d) signDocument_WhenTemplateNotFound_ShouldReturn404()
      - documentService.signDocument(any()) → TemplateNotFoundException fırlat
      - andExpect(status().isNotFound())
      - andExpect(jsonPath("$.error").isNotEmpty())
   
   e) getSignedDocumentById_WhenNotFound_ShouldReturn404()
      - documentService.findSignedDocumentById(999L) → DocumentNotFoundException
      - GET /api/documents/signed/999
      - andExpect(status().isNotFound())
─────────────────────────────────────────────
3. GlobalExceptionHandlerTest — paket: presentation.exception
   @WebMvcTest ile dummy controller üzerinden exception senaryoları test et
   veya @SpringBootTest + @AutoConfigureMockMvc kullan
   
   Test metodları:
   
   a) whenDocumentNotFound_ShouldReturnStandardErrorResponse()
      - ErrorResponse field'larını assert et: timestamp, status, error, message, path
      - jsonPath("$.timestamp").isNotEmpty()
      - jsonPath("$.path").value("/api/documents/signed/999")
   
   b) whenValidationFails_ShouldReturnFieldErrors()
      - MethodArgumentNotValidException tetiklendiğinde
      - status 400, message field adını içermeli
─────────────────────────────────────────────
Genel Kurallar:
- Her test metodunun adı: methodName_WhenCondition_ShouldExpectedBehavior formatında
- @DisplayName("...") Türkçe açıklama ekle
- Test içinde try/catch OLMAYACAK — JUnit 5 exception assertion kullan
- given/when/then yorum bloklarıyla bölümle
- Fixture/helper metodlar: private static DocumentTemplate dummyTemplate() { ... }
- pom.xml'e ek bağımlılık gerekmez — spring-boot-starter-test zaten Mockito + JUnit 5 içerir
```
---
## 📋 Kontrol Listesi (Her Prompt Sonrası)
- [ ] `@Valid` annotation controller'da var mı?
- [ ] Controller'da try/catch var mı? → **OLMAMALI**
- [ ] Service'de `@Transactional(readOnly=true)` sınıf seviyesinde mi?
- [ ] Write metodlarda `@Transactional` override var mı?
- [ ] Exception sınıfları `RuntimeException` extend ediyor mu?
- [ ] `GlobalExceptionHandler` tüm custom exception'ları yakalıyor mu?
- [ ] `findSignedDocuments` `Page<>` döndürüyor mu, `List<>` değil?
- [ ] KeyPair `@Bean` singleton olarak mı inject ediliyor?
- [ ] DTO'lar Java `record` mı, class değil mi?
- [ ] Stream'lerde `.toList()` mi kullanılıyor, `Collectors.toList()` değil mi?
- [ ] Test metodları `methodName_WhenCondition_ShouldExpected` formatında mı?
- [ ] Testlerde try/catch var mı? → **OLMAMALI**
- [ ] `@WebMvcTest` controller testlerinde `@MockBean` kullanıldı mı?
- [ ] Exception testleri `assertThatThrownBy` ile mi yazıldı?
