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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * ║                                                                  ║
 * ║  #5  HER İSTEKTE YENİ DB BAĞLANTISI                              ║
 * ║      Connection pool yok. Her şablon sorgusunda bağlantı        ║
 * ║      sıfırdan kurulur → gecikme + bağlantı limitine takılma.    ║
 * ║                                                                  ║
 * ║  #6  IN-MEMORY DEPOLAMA                                          ║
 * ║      İmzalı dokümanlar HashMap'te tutulur. Pod restart'ta       ║
 * ║      tüm imzalı dokümanlar KAYBOLUR.                            ║
 * ║                                                                  ║
 * ║  #7  PAGINATION YOK                                              ║
 * ║      Tüm imzalı dokümanlar tek seferde RAM'e çekilir.           ║
 * ║      Binlerce kayıtta OOM kaçınılmazdır.                        ║
 * ╚══════════════════════════════════════════════════════════════════╝
 *
 *  DERLEME : javac -cp postgresql.jar LegacyDocumentService.java
 *  ÇALIŞTIR: java -cp .:postgresql.jar LegacyDocumentService
 */
public class LegacyDocumentService {

    // ----------------------------------------------------------------
    // SORUN #1 — Sabit boyutlu, küçük thread havuzu
    // ----------------------------------------------------------------
    private static final int MAX_THREADS = 10;
    private final ExecutorService threadPool = Executors.newFixedThreadPool(MAX_THREADS);

    private static final AtomicInteger activeRequests   = new AtomicInteger(0);
    private static final AtomicInteger totalRequests    = new AtomicInteger(0);
    private static final AtomicInteger signedDocCounter = new AtomicInteger(0);

    // ----------------------------------------------------------------
    // SORUN #6 — In-memory depolama (restart'ta silinir)
    //            Yalnızca fallback; gerçek veriler DB'ye yazılır.
    // ----------------------------------------------------------------
    private static final Map<Integer, String> signedDocumentsCache = new HashMap<>();

    // ----------------------------------------------------------------
    // SORUN #5 — DB bağlantı bilgileri env variable'lardan okunur
    //            (Connection pool YOK — her sorgu için yeni bağlantı)
    // ----------------------------------------------------------------
    private static final String DB_URL  = System.getenv("DB_URL")  != null
            ? System.getenv("DB_URL")
            : "jdbc:postgresql://localhost:5432/defaultdb?sslmode=require";
    private static final String DB_USER = System.getenv("DB_USER") != null
            ? System.getenv("DB_USER")
            : "postgres";
    private static final String DB_PASS = System.getenv("DB_PASS") != null
            ? System.getenv("DB_PASS")
            : "";

    // ----------------------------------------------------------------
    // SORUN #5 — Her sorguda yeni Connection (no pool)
    // ----------------------------------------------------------------
    private static Connection getNewConnection() throws Exception {
        // Yapay gecikme: bağlantı kurulumu simülasyonu
        Thread.sleep(300); // SORUN #2 ile birlikte thread'i bloklar
        return DriverManager.getConnection(DB_URL, DB_USER, DB_PASS);
    }

    // ----------------------------------------------------------------
    // Sunucuyu başlat
    // ----------------------------------------------------------------
    public void start() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(threadPool); // SORUN #1: max 10 thread

        server.createContext("/api/templates",       new TemplatesHandler());
        server.createContext("/api/documents/sign",  new SignDocumentHandler());
        server.createContext("/api/documents/signed",new SignedListHandler());
        server.createContext("/api/health",          new HealthHandler());
        server.createContext("/api/documents/content", new SignedContentHandler());
        server.createContext("/api/documents/pdf",     new SignedPdfHandler());
        server.createContext("/api/status",          new StatusHandler());

        server.start();
        System.out.println("[Legacy] Server started on :8080");
        System.out.println("[Legacy] DB_URL  = " + DB_URL);
        System.out.println("[Legacy] DB_USER = " + DB_USER);
    }

    public static void main(String[] args) throws IOException {
        new LegacyDocumentService().start();
    }

    // ================================================================
    // GET /api/templates  — DB'den şablonları çek
    // ================================================================
    class TemplatesHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            totalRequests.incrementAndGet();
            activeRequests.incrementAndGet();
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendResponse(ex, 405, "{\"error\":\"Method Not Allowed\"}");
                    return;
                }

                // SORUN #5 — yeni bağlantı aç
                List<Map<String, String>> templates = new ArrayList<>();
                try (Connection conn = getNewConnection()) {
                    String sql = "SELECT id, name, description, version FROM document_templates ORDER BY id";
                    try (PreparedStatement ps = conn.prepareStatement(sql);
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, String> t = new HashMap<>();
                            t.put("id",          String.valueOf(rs.getInt("id")));
                            t.put("name",        rs.getString("name"));
                            t.put("description", rs.getString("description"));
                            t.put("version",     rs.getString("version"));
                            templates.add(t);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Legacy] DB error in /api/templates: " + e.getMessage());
                    sendResponse(ex, 500, "{\"error\":\"DB connection failed: " + e.getMessage().replace("\"","'") + "\"}");
                    return;
                }

                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < templates.size(); i++) {
                    if (i > 0) sb.append(",");
                    Map<String, String> t = templates.get(i);
                    sb.append("{")
                      .append("\"id\":").append(t.get("id")).append(",")
                      .append("\"name\":\"").append(t.get("name")).append("\",")
                      .append("\"description\":\"").append(t.get("description")).append("\",")
                      .append("\"version\":\"").append(t.get("version")).append("\"")
                      .append("}");
                }
                sb.append("]");
                sendResponse(ex, 200, sb.toString());

            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }

    // ================================================================
    // POST /api/documents/sign  — Şablon DB'den çek, imzala, DB'ye kaydet
    // ================================================================
    class SignDocumentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            totalRequests.incrementAndGet();
            activeRequests.incrementAndGet();
            try {
                if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendResponse(ex, 405, "{\"error\":\"Method Not Allowed\"}");
                    return;
                }

                String body = readBody(ex);
                int templateId   = parseIntField(body, "templateId");
                String customerId   = parseField(body, "customerId");
                String customerName = parseField(body, "customerName");

                if (templateId <= 0 || customerId.isEmpty() || customerName.isEmpty()) {
                    sendResponse(ex, 400, "{\"error\":\"templateId, customerId ve customerName zorunludur\"}");
                    return;
                }

                // SORUN #3 — büyük byte dizisi heap'e
                byte[] documentData = new byte[512 * 1024];
                for (int i = 0; i < documentData.length; i++) documentData[i] = (byte)(i % 256);

                // SORUN #2 — thread'i 3 sn bloklayan işlem simülasyonu
                try { Thread.sleep(3000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

                // SORUN #4 — her istekte RSA KeyPair üretimi
                String signatureHex;
                try {
                    KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                    kpg.initialize(2048);
                    KeyPair kp = kpg.generateKeyPair();
                    Signature sig = Signature.getInstance("SHA256withRSA");
                    sig.initSign(kp.getPrivate());
                    sig.update(documentData, 0, Math.min(1024, documentData.length));
                    byte[] sigBytes = sig.sign();
                    StringBuilder hex = new StringBuilder();
                    for (byte b : sigBytes) hex.append(String.format("%02x", b));
                    signatureHex = hex.toString();
                } catch (Exception e) {
                    sendResponse(ex, 500, "{\"error\":\"Signature error: " + e.getMessage() + "\"}");
                    return;
                }

                // SORUN #5 — yeni bağlantı aç, şablonu çek ve imzalı dokümanı kaydet
                int newDocId;
                try (Connection conn = getNewConnection()) {
                    // Şablonu çek
                    String templateContent;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT content FROM document_templates WHERE id=?")) {
                        ps.setInt(1, templateId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                sendResponse(ex, 404, "{\"error\":\"Template not found: " + templateId + "\"}");
                                return;
                            }
                            templateContent = rs.getString("content");
                        }
                    }

                    // Değişken yerine koy
                    templateContent = templateContent
                        .replace("{{MUSTERI_ADI}}", customerName)
                        .replace("{{TC_NO}}", customerId)
                        .replace("{{FIRMA_ADI}}", customerName)
                        .replace("{{KREDI_TUTARI}}", "0")
                        .replace("{{VADE}}", "0")
                        .replace("{{FAIZ}}", "0")
                        .replace("{{LIMIT}}", "0")
                        .replace("{{KART_TURU}}", "Standart")
                        .replace("{{ADRES}}", "-")
                        .replace("{{VERGI_NO}}", customerId);

                    // SORUN #6 — aynı zamanda DB'ye de yaz (ama cache'de de tutuyoruz)
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO signed_documents(template_id,customer_id,customer_name,signature_data) VALUES(?,?,?,?) RETURNING id")) {
                        ps.setInt(1, templateId);
                        ps.setString(2, customerId);
                        ps.setString(3, customerName);
                        ps.setString(4, signatureHex.substring(0, Math.min(64, signatureHex.length())));
                        try (ResultSet rs = ps.executeQuery()) {
                            rs.next();
                            newDocId = rs.getInt(1);
                        }
                    }

                    // SORUN #6 — in-memory cache (gereksiz duplicate)
                    int cacheKey = signedDocCounter.incrementAndGet();
                    signedDocumentsCache.put(cacheKey, templateContent);

                } catch (Exception e) {
                    System.err.println("[Legacy] DB error in /api/documents/sign: " + e.getMessage());
                    sendResponse(ex, 500, "{\"error\":\"DB error: " + e.getMessage().replace("\"","'") + "\"}");
                    return;
                }

                String resp = "{\"documentId\":" + newDocId
                    + ",\"customerId\":\"" + customerId + "\""
                    + ",\"customerName\":\"" + customerName + "\""
                    + ",\"templateId\":" + templateId
                    + ",\"signatureLength\":" + signatureHex.length()
                    + ",\"status\":\"SIGNED\""
                    + ",\"documentUrl\":\"http://188.166.202.183/api/documents/pdf?id=" + newDocId + "\""
                    + ",\"localUrl\":\"http://localhost:8080/api/documents/pdf?id=" + newDocId + "\""
                    + "}";
                sendResponse(ex, 200, resp);

            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }

    // ================================================================
    // GET /api/documents/signed  — SORUN #7: pagination yok, hepsi RAM'e
    // ================================================================
    class SignedListHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            totalRequests.incrementAndGet();
            activeRequests.incrementAndGet();
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendResponse(ex, 405, "{\"error\":\"Method Not Allowed\"}");
                    return;
                }

                // SORUN #7 — tüm kayıtlar tek sorguda, LIMIT/OFFSET yok
                List<Map<String, Object>> docs = new ArrayList<>();
                try (Connection conn = getNewConnection()) {
                    String sql = "SELECT id, template_id, customer_id, customer_name, signed_at FROM signed_documents ORDER BY id";
                    try (PreparedStatement ps = conn.prepareStatement(sql);
                         ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            Map<String, Object> d = new HashMap<>();
                            d.put("id",           rs.getInt("id"));
                            d.put("templateId",   rs.getInt("template_id"));
                            d.put("customerId",   rs.getString("customer_id"));
                            d.put("customerName", rs.getString("customer_name"));
                            d.put("signedAt",     String.valueOf(rs.getTimestamp("signed_at")));
                            docs.add(d);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[Legacy] DB error in /api/documents/signed: " + e.getMessage());
                    sendResponse(ex, 500, "{\"error\":\"DB error: " + e.getMessage().replace("\"","'") + "\"}");
                    return;
                }

                StringBuilder sb = new StringBuilder("{\"total\":" + docs.size() + ",\"documents\":[");
                for (int i = 0; i < docs.size(); i++) {
                    if (i > 0) sb.append(",");
                    Map<String, Object> d = docs.get(i);
                    sb.append("{")
                      .append("\"id\":").append(d.get("id")).append(",")
                      .append("\"templateId\":").append(d.get("templateId")).append(",")
                      .append("\"customerId\":\"").append(d.get("customerId")).append("\",")
                      .append("\"customerName\":\"").append(d.get("customerName")).append("\",")
                      .append("\"signedAt\":\"").append(d.get("signedAt")).append("\"")
                      .append("}");
                }
                sb.append("]}");
                sendResponse(ex, 200, sb.toString());

            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }

    // ================================================================
    // GET /api/documents/pdf?id=X  — Imzali dokumani PDF olarak dondur
    // ================================================================
    class SignedPdfHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            totalRequests.incrementAndGet();
            activeRequests.incrementAndGet();
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendResponse(ex, 405, "{\"error\":\"Method Not Allowed\"}");
                    return;
                }

                String query = ex.getRequestURI().getQuery();
                int docId = -1;
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("id=")) {
                            try { docId = Integer.parseInt(param.substring(3)); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                if (docId < 0) {
                    sendResponse(ex, 400, "{\"error\":\"id parametresi zorunludur. Ornek: /api/documents/pdf?id=1\"}");
                    return;
                }

                String customerName = "Musteri", customerId = "-", templateName = "Sozlesme",
                       version = "-", signedAt = "-", content = "Icerik bulunamadi.";

                // SORUN #5 — yeni baglanti ac
                try (Connection conn = getNewConnection()) {
                    String sql = "SELECT sd.id, sd.customer_id, sd.customer_name, sd.signed_at, "
                        + "dt.name as template_name, dt.version, dt.content "
                        + "FROM signed_documents sd "
                        + "JOIN document_templates dt ON sd.template_id = dt.id "
                        + "WHERE sd.id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setInt(1, docId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                sendResponse(ex, 404, "{\"error\":\"Document not found: " + docId + "\"}");
                                return;
                            }
                            customerName = rs.getString("customer_name");
                            customerId   = rs.getString("customer_id");
                            templateName = rs.getString("template_name");
                            version      = rs.getString("version");
                            signedAt     = String.valueOf(rs.getTimestamp("signed_at"));
                            content      = rs.getString("content")
                                .replace("{{MUSTERI_ADI}}", customerName)
                                .replace("{{TC_NO}}",       customerId)
                                .replace("{{FIRMA_ADI}}",   customerName)
                                .replace("{{KREDI_TUTARI}}","0")
                                .replace("{{VADE}}",        "0")
                                .replace("{{FAIZ}}",        "0")
                                .replace("{{LIMIT}}",       "0")
                                .replace("{{KART_TURU}}",   "Standart")
                                .replace("{{ADRES}}",       "-")
                                .replace("{{VERGI_NO}}",    customerId);
                        }
                    }
                } catch (Exception e) {
                    sendResponse(ex, 500, "{\"error\":\"DB error: " + e.getMessage().replace("\"","'") + "\"}");
                    return;
                }

                byte[] pdfBytes = buildPdf(docId, customerName, customerId, templateName, version, signedAt, content);
                ex.getResponseHeaders().set("Content-Type", "application/pdf");
                ex.getResponseHeaders().set("Content-Disposition",
                    "inline; filename=\"imzali-dokuman-" + docId + ".pdf\"");
                ex.sendResponseHeaders(200, pdfBytes.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(pdfBytes);
                }
            } finally {
                activeRequests.decrementAndGet();
            }
        }

        private byte[] buildPdf(int docId, String customerName, String customerId,
                                String templateName, String version, String signedAt,
                                String content) throws IOException {
            // Turkce karakterleri ASCII'ye donustur (Type1 font sinirlamasi)
            String safeName     = toAscii(customerName);
            String safeTpl      = toAscii(templateName);
            String safeContent  = toAscii(content);
            String safeDate     = signedAt.length() > 19 ? signedAt.substring(0, 19) : signedAt;

            // PDF stream icerik satirlari (max 80 karakter)
            List<String> lines = new ArrayList<>();
            lines.add("IMZALI DOKUMAN #" + docId);
            lines.add("==================================================");
            lines.add("Sabloon : " + safeTpl + " (" + version + ")");
            lines.add("Musteri : " + safeName);
            lines.add("Musteri ID: " + customerId);
            lines.add("Imza Tarihi: " + safeDate);
            lines.add("");
            lines.add("--- SOZLESME ICERIK ---");
            // Uzun metni 75 karlik satirlara bol
            for (String raw : safeContent.split("\\n")) {
                raw = raw.trim();
                while (raw.length() > 75) {
                    lines.add(raw.substring(0, 75));
                    raw = raw.substring(75);
                }
                if (!raw.isEmpty()) lines.add(raw);
            }
            lines.add("");
            lines.add("==================================================");
            lines.add("Bu dokuman Legacy Document Service tarafindan");
            lines.add("otomatik olarak imzalanmistir.");

            // PDF stream olustur
            StringBuilder stream = new StringBuilder();
            stream.append("BT\n");
            stream.append("/F1 13 Tf\n");
            float y = 750;
            for (String line : lines) {
                String safe = line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)");
                stream.append(String.format("50 %.0f Td (%s) Tj\n", y, safe));
                y -= 16;
                if (y < 50) { stream.append("ET\nBT\n/F1 13 Tf\n"); y = 750; }
            }
            stream.append("ET\n");
            byte[] streamBytes = stream.toString().getBytes("ISO-8859-1");

            // PDF yapisini olustur
            StringBuilder pdf = new StringBuilder();
            pdf.append("%PDF-1.4\n");

            int[] offsets = new int[6];
            offsets[1] = pdf.length();
            pdf.append("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");
            offsets[2] = pdf.length();
            pdf.append("2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");
            offsets[3] = pdf.length();
            pdf.append("3 0 obj\n<< /Type /Page /Parent 2 0 R "
                + "/Resources << /Font << /F1 4 0 R >> >> "
                + "/MediaBox [0 0 595 842] /Contents 5 0 R >>\nendobj\n");
            offsets[4] = pdf.length();
            pdf.append("4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica "
                + "/Encoding /WinAnsiEncoding >>\nendobj\n");
            offsets[5] = pdf.length();
            pdf.append("5 0 obj\n<< /Length " + streamBytes.length + " >>\nstream\n");

            byte[] pdfStart  = pdf.toString().getBytes("ISO-8859-1");
            byte[] pdfMiddle = "\nendstream\nendobj\n".getBytes("ISO-8859-1");

            int xrefOffset = pdfStart.length + streamBytes.length + pdfMiddle.length;
            StringBuilder xref = new StringBuilder();
            xref.append("xref\n0 6\n0000000000 65535 f \n");
            for (int i = 1; i <= 5; i++)
                xref.append(String.format("%010d 00000 n \n", offsets[i]));
            xref.append("trailer\n<< /Size 6 /Root 1 0 R >>\n");
            xref.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
            byte[] pdfEnd = xref.toString().getBytes("ISO-8859-1");

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(pdfStart);
            out.write(streamBytes);
            out.write(pdfMiddle);
            out.write(pdfEnd);
            return out.toByteArray();
        }

        private String toAscii(String s) {
            if (s == null) return "";
            return s.replace("\u00e7","c").replace("\u00c7","C")
                    .replace("\u011f","g").replace("\u011e","G")
                    .replace("\u0131","i").replace("\u0130","I")
                    .replace("\u00f6","o").replace("\u00d6","O")
                    .replace("\u015f","s").replace("\u015e","S")
                    .replace("\u00fc","u").replace("\u00dc","U")
                    .replace("\u00e2","a").replace("\u00ee","i").replace("\u00fb","u");
        }
    }

    // ================================================================
    // GET /api/documents/content?id=X  — İmzalı dokümanın şablon içeriğini göster
    // ================================================================
    class SignedContentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            totalRequests.incrementAndGet();
            activeRequests.incrementAndGet();
            try {
                if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
                    sendResponse(ex, 405, "{\"error\":\"Method Not Allowed\"}");
                    return;
                }

                String query = ex.getRequestURI().getQuery();
                int docId = -1;
                if (query != null) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("id=")) {
                            try { docId = Integer.parseInt(param.substring(3)); } catch (NumberFormatException ignored) {}
                        }
                    }
                }
                // /api/documents/signed/123 path formatını da destekle
                String path = ex.getRequestURI().getPath();
                String[] parts = path.split("/");
                if (docId < 0 && parts.length > 0) {
                    try { docId = Integer.parseInt(parts[parts.length - 1]); } catch (NumberFormatException ignored) {}
                }

                if (docId < 0) {
                    sendResponse(ex, 400, "{\"error\":\"id parametresi zorunludur. Örnek: /api/documents/content?id=1\"}");
                    return;
                }

                // SORUN #5 — yeni bağlantı aç
                try (Connection conn = getNewConnection()) {
                    String sql = "SELECT sd.id, sd.customer_id, sd.customer_name, sd.signed_at, "
                        + "dt.name as template_name, dt.version, dt.content "
                        + "FROM signed_documents sd "
                        + "JOIN document_templates dt ON sd.template_id = dt.id "
                        + "WHERE sd.id = ?";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setInt(1, docId);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                sendResponse(ex, 404, "{\"error\":\"Document not found: " + docId + "\"}");
                                return;
                            }
                            String content = rs.getString("content")
                                .replace("{{MUSTERI_ADI}}", rs.getString("customer_name"))
                                .replace("{{TC_NO}}", rs.getString("customer_id"))
                                .replace("{{FIRMA_ADI}}", rs.getString("customer_name"))
                                .replace("\"", "'");
                            String body = "{\"id\":" + rs.getInt("id")
                                + ",\"customerId\":\"" + rs.getString("customer_id") + "\""
                                + ",\"customerName\":\"" + rs.getString("customer_name") + "\""
                                + ",\"templateName\":\"" + rs.getString("template_name") + "\""
                                + ",\"version\":\"" + rs.getString("version") + "\""
                                + ",\"signedAt\":\"" + rs.getTimestamp("signed_at") + "\""
                                + ",\"content\":\"" + content.replace("\n", "\\n") + "\""
                                + "}";
                            sendResponse(ex, 200, body);
                        }
                    }
                } catch (Exception e) {
                    sendResponse(ex, 500, "{\"error\":\"DB error: " + e.getMessage().replace("\"","'") + "\"}");
                }
            } finally {
                activeRequests.decrementAndGet();
            }
        }
    }

    // ================================================================
    // GET /api/health
    // ================================================================
    class HealthHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String dbStatus = "OK";
            try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASS)) {
                conn.isValid(2);
            } catch (Exception e) {
                dbStatus = "ERROR: " + e.getMessage().replace("\"","'");
            }
            String body = "{\"status\":\"UP\",\"db\":\"" + dbStatus + "\"}";
            sendResponse(ex, 200, body);
        }
    }

    // ================================================================
    // GET /api/status
    // ================================================================
    class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange ex) throws IOException {
            String body = "{"
                + "\"service\":\"legacy-document-service\","
                + "\"version\":\"1.0.0-legacy\","
                + "\"activeRequests\":"  + activeRequests.get()  + ","
                + "\"totalRequests\":"   + totalRequests.get()   + ","
                + "\"maxThreads\":"      + MAX_THREADS            + ","
                + "\"signedDocsCache\":" + signedDocCounter.get() + ","
                + "\"knownIssues\":["
                + "\"#1 Limited thread pool (max 10)\","
                + "\"#2 Blocking architecture (3s sleep per request)\","
                + "\"#3 Large byte array per request (OOM risk)\","
                + "\"#4 RSA KeyPair generated per request (CPU waste)\","
                + "\"#5 New DB connection per request (no pool)\","
                + "\"#6 In-memory cache duplicates DB data\","
                + "\"#7 No pagination on signed documents\""
                + "]}";
            sendResponse(ex, 200, body);
        }
    }

    // ================================================================
    // Yardımcı metodlar
    // ================================================================
    private static void sendResponse(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody();
             ByteArrayOutputStream buf = new ByteArrayOutputStream()) {
            byte[] tmp = new byte[1024];
            int n;
            while ((n = is.read(tmp)) != -1) buf.write(tmp, 0, n);
            return buf.toString("UTF-8");
        }
    }

    private static String parseField(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx);
        if (colon < 0) return "";
        int start = json.indexOf("\"", colon);
        if (start < 0) return "";
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return "";
        return json.substring(start + 1, end);
    }

    private static int parseIntField(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return -1;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return -1;
        StringBuilder num = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        try { return Integer.parseInt(num.toString()); } catch (NumberFormatException e) { return -1; }
    }

    private static void simulateSlowOperation() {
        try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
