package com.coderspace.documentservice.presentation.controller;

import com.coderspace.documentservice.application.dto.request.SignDocumentRequest;
import com.coderspace.documentservice.application.dto.response.DocumentTemplateResponse;
import com.coderspace.documentservice.application.dto.response.PagedResponse;
import com.coderspace.documentservice.application.dto.response.SignedDocumentResponse;
import com.coderspace.documentservice.application.service.DocumentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Validated
public class DocumentController {

    private final DocumentService documentService;

    /**
     * Tüm şablonları listeler.
     * GET /api/templates → 200 OK
     */
    @GetMapping("/templates")
    public ResponseEntity<List<DocumentTemplateResponse>> getTemplates() {
        return ResponseEntity.ok(documentService.findAllTemplates());
    }

    /**
     * Yeni bir belge imzalar.
     * POST /api/documents/sign → 201 CREATED
     * Bean Validation @Valid ile tetiklenir; hata GlobalExceptionHandler'a devredilir.
     */
    @PostMapping("/documents/sign")
    public ResponseEntity<SignedDocumentResponse> signDocument(
            @Valid @RequestBody SignDocumentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.signDocument(request));
    }

    /**
     * FIX #7 — SELECT * + List<> yerine sayfalı yanıt.
     * GET /api/documents/signed?page=0&size=20 → 200 OK
     */
    @GetMapping("/documents/signed")
    public ResponseEntity<PagedResponse<SignedDocumentResponse>> getSignedDocuments(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<SignedDocumentResponse> result = documentService.findSignedDocuments(page, size);
        PagedResponse<SignedDocumentResponse> response = new PagedResponse<>(
                result.getContent(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
        return ResponseEntity.ok(response);
    }

    /**
     * Belirli bir imzalı belgeyi ID ile getirir.
     * GET /api/documents/signed/{id} → 200 OK
     * Bulunamazsa GlobalExceptionHandler → 404 NOT_FOUND.
     */
    @GetMapping("/documents/signed/{id}")
    public ResponseEntity<SignedDocumentResponse> getSignedDocumentById(
            @PathVariable Long id) {
        return ResponseEntity.ok(documentService.findSignedDocumentById(id));
    }

    /**
     * İmzalı belgeyi PDF formatında indir.
     * GET /api/documents/pdf/{id} → 200 OK (application/pdf)
     */
    @GetMapping("/documents/pdf/{id}")
    public ResponseEntity<byte[]> getDocumentAsPdf(@PathVariable Long id) {
        SignedDocumentResponse doc = documentService.findSignedDocumentById(id);
        byte[] pdfBytes = documentService.generatePdf(id);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("inline",
                "imzali-dokuman-" + id + ".pdf");
        return new ResponseEntity<>(pdfBytes, headers, HttpStatus.OK);
    }

    /**
     * Servis sağlık durumu.
     * GET /api/health → 200 OK
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "modern-document-service",
                "version", "2.0.0-MODERN"
        ));
    }

    /**
     * Legacy anti-pattern'lerin çözüm durumunu ve aktif thread bilgisini döner.
     * GET /api/status → 200 OK
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Thread currentThread = Thread.currentThread();
        return ResponseEntity.ok(Map.of(
                "service", "modern-document-service",
                "activeThread", Map.of(
                        "name", currentThread.getName(),
                        "isVirtual", currentThread.isVirtual()
                ),
                "fixedIssues", List.of(
                        "FIX #1 — Virtual Threads: MAX_THREADS=10 sınırı kaldırıldı",
                        "FIX #2 — Blocking I/O: Thread.sleep(3000) elimine edildi",
                        "FIX #3 — OOM Riski: 512KB per-request buffer kaldırıldı",
                        "FIX #4 — CPU İsrafı: RSA KeyPair singleton bean olarak üretiliyor",
                        "FIX #5 — Connection Pool: DriverManager yerine HikariCP kullanılıyor",
                        "FIX #6 — In-Memory State: HashMap yerine PostgreSQL kullanılıyor",
                        "FIX #7 — Pagination: SELECT * yerine Page<T> + Pageable kullanılıyor"
                )
        ));
    }
}
