import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ╔══════════════════════════════════════════════════════════════════╗
 * ║         AKBANK — Legacy Document Service  (Java 8)              ║
 * ║                  "The Legacy Rescue" Senaryosu                  ║
 * ╠══════════════════════════════════════════════════════════════════╣
 * ║  TANIMLANAN KRİTİK SORUNLAR:                                    ║
 * ║                                                                  ║
 * ║  #1  KISITLI THREAD HAVUZU                                       ║
 * ║      Yalnızca 10 Platform Thread. 11. müşteri kuyruğa girer,    ║
 * ║      yanıt bekler. Trafik artınca sistem felç olur.             ║
 * ║                                                                  ║
 * ║  #2  BLOKLAYAN (BLOCKING) MİMARİ                                 ║
 * ║      Her istek thread'i ortalama 3 sn boyunca uyutur.           ║
 * ║      Thread uyurken hiçbir iş yapamaz, kaynak boşa harcanır.    ║
 * ║                                                                  ║
 * ║  #3  BELLEK SIZINTISI (OOM) RİSKİ                                ║
 * ║      Her istek için devasa byte[] dizisi heap'e yüklenir.       ║
 * ║      GC baskısı artar → Pod "Out Of Memory" ile çöker.          ║
 * ║                                                                  ║
 * ║  #4  HER İSTEKTE ANAHTAR ÜRETİMİ                                 ║
 * ║      2048-bit RSA KeyPair her çağrıda sıfırdan oluşturuluyor.   ║
 * ║      Bu işlem CPU açısından son derece maliyetlidir.            ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 *  DERLEME : javac LegacyDocumentService.java
 *  ÇALIŞTIR: java LegacyDocumentService
 */
public class LegacyDocumentService {

    // ----------------------------------------------------------------
    // SORUN #1 — Sabit boyutlu, küçük thread havuzu
    // 10 thread dolduğunda yeni istekler kuyruğa alınır.
    // Kuyruk da dolduğunda RejectedExecutionException fırlatılır.
    // ----------------------------------------------------------------
    private static final int MAX_THREADS = 10;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(MAX_THREADS);

    private static final AtomicInteger activeRequests = new AtomicInteger(0);
    private static final AtomicInteger totalRequests  = new AtomicInteger(0);

    // ================================================================
    //  SERVER BAŞLATMA
    // ================================================================
    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/api/documents/process", new DocumentHandler());
        server.createContext("/api/health",            new HealthHandler());
        server.createContext("/api/status",            new StatusHandler());

        // Aynı kısıtlı havuzu server executor olarak kullanıyoruz.
        // Bu da HTTP kabul işlemini ek olarak yavaşlatır.
        server.setExecutor(threadPool);
        server.start();

        System.out.println("╔═══════════════════════════════════════════════════╗");
        System.out.println("║   AKBANK  —  Legacy Document Service (Java 8)     ║");
        System.out.println("║   http://localhost:8080                           ║");
        System.out.println("╠═══════════════════════════════════════════════════╣");
        System.out.println("║  [!] Maks. eşzamanlı istek : " + MAX_THREADS + "                      ║");
        System.out.println("║  [!] İşlem süresi / istek  : ~3 saniye (bloklu)  ║");
        System.out.println("║  [!] OOM riski             : YÜKSEK               ║");
        System.out.println("╚═══════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("  Doküman İşle  → POST http://localhost:8080/api/documents/process");
        System.out.println("  Sağlık        → GET  http://localhost:8080/api/health");
        System.out.println("  Durum         → GET  http://localhost:8080/api/status");
        System.out.println();
        System.out.println("  [İPUCU] Sorunu canlı görmek için paralel 15 istek gönderin:");
        System.out.println("  for i in $(seq 1 15); do curl -s -X POST http://localhost:8080/api/documents/process -d \"Akbank-Doc-$i\" & done");
        System.out.println();
    }

    // ================================================================
    //  HANDLER: POST /api/documents/process
    // ================================================================
    class DocumentHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {

            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                sendResponse(exchange, 405, json(
                    "\"error\"", "\"Sadece POST metodu kabul edilir\""
                ));
                return;
            }

            int requestId = totalRequests.incrementAndGet();
            int active    = activeRequests.incrementAndGet();

            System.out.println("┌─ [REQUEST-" + requestId + "] Yeni istek alındı");
            System.out.println("│  Thread       : " + Thread.currentThread().getName());
            System.out.println("│  Aktif istek  : " + active + " / " + MAX_THREADS);

            if (active >= MAX_THREADS) {
                System.out.println("│  [!!!] THREAD HAVUZU DOLU — Yeni istekler KUYRUKTA bekliyor!");
            }

            try {
                String documentContent = readBody(exchange.getRequestBody());
                if (documentContent.isEmpty()) {
                    documentContent = "Akbank Varsayilan Dokumani - Musteri #" + requestId;
                }

                long start = System.currentTimeMillis();

                // ADIM 1 — PDF dönüşümü (1 sn blokluyor)
                byte[] pdfData = convertToPdf(documentContent);

                // ADIM 2 — RSA imzalama (2 sn blokluyor)
                byte[] signature = signDocument(pdfData);

                long durationMs = System.currentTimeMillis() - start;

                String body = "{\n"
                    + "  \"requestId\"       : " + requestId + ",\n"
                    + "  \"status\"          : \"SUCCESS\",\n"
                    + "  \"documentSizeByte\": " + pdfData.length + ",\n"
                    + "  \"signatureSizeByte\": " + signature.length + ",\n"
                    + "  \"processingTimeMs\": " + durationMs + ",\n"
                    + "  \"threadName\"      : \"" + Thread.currentThread().getName() + "\",\n"
                    + "  \"warning\"         : \"Bu thread " + durationMs + " ms boyunca BLOKLANMIŞTIR.\"\n"
                    + "}";

                sendResponse(exchange, 200, body);

                System.out.println("│  Süre         : " + durationMs + " ms");
                System.out.println("└─ [REQUEST-" + requestId + "] Tamamlandı.");

            } catch (Exception e) {
                sendResponse(exchange, 500, json("\"error\"", "\"" + e.getMessage() + "\""));
                System.err.println("└─ [HATA] Request #" + requestId + ": " + e.getMessage());
            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }

    // ================================================================
    //  HANDLER: GET /api/health
    // ================================================================
    class HealthHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            int active = activeRequests.get();
            String status = (active >= MAX_THREADS) ? "DEGRADED" : "UP";

            String body = "{\n"
                + "  \"status\"     : \"" + status + "\",\n"
                + "  \"service\"    : \"LegacyDocumentService\",\n"
                + "  \"javaVersion\": \"" + System.getProperty("java.version") + "\",\n"
                + "  \"maxThreads\" : " + MAX_THREADS + ",\n"
                + "  \"activeNow\"  : " + active + ",\n"
                + "  \"warning\"    : \""
                + (active >= MAX_THREADS
                    ? "Thread havuzu DOLU! Yeni istekler sırada bekliyor!"
                    : "Sistem calisiyor, ancak mimari kirilgan.")
                + "\"\n"
                + "}";

            sendResponse(exchange, 200, body);
        }
    }

    // ================================================================
    //  HANDLER: GET /api/status
    // ================================================================
    class StatusHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Runtime rt       = Runtime.getRuntime();
            long usedMB      = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMB       = rt.maxMemory() / (1024 * 1024);
            int  active      = activeRequests.get();
            int  total       = totalRequests.get();

            String body = "{\n"
                + "  \"service\"             : \"Akbank Legacy Document Service\",\n"
                + "  \"version\"             : \"Java 8 — Bloklayan Mimari\",\n"
                + "  \"totalRequests\"       : " + total + ",\n"
                + "  \"activeRequests\"      : " + active + ",\n"
                + "  \"maxConcurrentCapacity\": " + MAX_THREADS + ",\n"
                + "  \"heapUsedMB\"          : " + usedMB + ",\n"
                + "  \"heapMaxMB\"           : " + maxMB + ",\n"
                + "  \"knownProblems\": [\n"
                + "    \"Yalnizca " + MAX_THREADS + " esz. istek islenebilir — 11.'si KUYRUKTA bekler\",\n"
                + "    \"Her istek ~3 sn thread'i BLOKLAR, bos bekleme = kaynak israfi\",\n"
                + "    \"Her istekte devasa byte[] heap'e yuklenir — OOM riski\",\n"
                + "    \"Her istekte 2048-bit RSA KeyPair uretilir — CPU israfi\"\n"
                + "  ]\n"
                + "}";

            sendResponse(exchange, 200, body);
        }
    }

    // ================================================================
    //  LEGACY BUSINESS LOGIC  (Sorunların kalbi burası)
    // ================================================================

    /**
     * SORUN #2 + #3
     * - Thread 1000 ms boyunca Thread.sleep() ile BLOKLANIR.
     * - İçerik boyutunun 100 katı büyüklüğünde byte[] heap'e yazılır.
     *   Yüksek trafikte GC baskısı → "java.lang.OutOfMemoryError: Java heap space"
     */
    private byte[] convertToPdf(String content) throws InterruptedException {
        System.out.println("│  [PDF] Donusum basliyor... (1 sn blokluyor)");

        // SORUN #3 — Her istek için gereksiz büyük bellek tahsisi
        byte[] contentBytes   = content.getBytes();
        byte[] pdfSimulation  = new byte[contentBytes.length * 100]; // 100x şişirme!
        System.arraycopy(contentBytes, 0, pdfSimulation, 0, contentBytes.length);

        // SORUN #2 — Senkron bekleme: Thread bu süre boyunca hiçbir iş yapamaz
        Thread.sleep(1000);

        System.out.println("│  [PDF] Tamamlandi. Boyut: " + pdfSimulation.length + " byte");
        return pdfSimulation;
    }

    /**
     * SORUN #2 + #4
     * - Thread 2000 ms boyunca Thread.sleep() ile BLOKLANIR.
     * - Her çağrıda 2048-bit RSA KeyPair sıfırdan üretilir.
     *   Bu, CPU açısından son derece pahalı bir işlemdir.
     *   Gerçek sistemde bu key bir kez üretilip cache'lenmelidir.
     */
    private byte[] signDocument(byte[] pdfData) throws Exception {
        System.out.println("│  [RSA] 2048-bit imzalama basliyor... (2 sn blokluyor)");

        // SORUN #4 — Her istekte yeni KeyPair üretimi: çok maliyetli!
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("RSA");
        keyGen.initialize(2048);
        KeyPair pair = keyGen.generateKeyPair();

        Signature rsa = Signature.getInstance("SHA256withRSA");
        rsa.initSign(pair.getPrivate());
        rsa.update(pdfData);

        // SORUN #2 — Dış servis gecikmesi simülasyonu (e-imza gateway, HSM vb.)
        Thread.sleep(2000);

        byte[] signature = rsa.sign();
        System.out.println("│  [RSA] Imzalandi. Imza boyutu: " + signature.length + " byte");
        return signature;
    }

    // ================================================================
    //  YARDIMCI METODLAR
    // ================================================================

    /** Java 8 uyumlu request body okuyucu (InputStream.readAllBytes() Java 9+) */
    private String readBody(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int bytesRead;
        while ((bytesRead = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toString("UTF-8");
    }

    private void sendResponse(HttpExchange exchange, int statusCode, String body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        byte[] bytes = body.getBytes("UTF-8");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    /** Tek satırlık mini JSON yardımcısı */
    private String json(String key, String value) {
        return "{ " + key + ": " + value + " }";
    }

    // ================================================================
    //  MAIN
    // ================================================================
    public static void main(String[] args) throws IOException {
        new LegacyDocumentService().start();

        // JVM'yi canlı tutmak için — gerçek uygulamada lifecycle yönetimi gerekir
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
