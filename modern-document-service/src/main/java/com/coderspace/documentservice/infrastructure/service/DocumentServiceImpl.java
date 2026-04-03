package com.coderspace.documentservice.infrastructure.service;

import com.coderspace.documentservice.application.dto.request.SignDocumentRequest;
import com.coderspace.documentservice.application.dto.response.DocumentTemplateResponse;
import com.coderspace.documentservice.application.dto.response.SignedDocumentResponse;
import com.coderspace.documentservice.application.exception.DocumentNotFoundException;
import com.coderspace.documentservice.application.exception.SignatureProcessingException;
import com.coderspace.documentservice.application.exception.TemplateNotFoundException;
import com.coderspace.documentservice.application.service.DocumentService;
import com.coderspace.documentservice.domain.entity.DocumentTemplate;
import com.coderspace.documentservice.domain.entity.SignedDocument;
import com.coderspace.documentservice.domain.repository.DocumentTemplateRepository;
import com.coderspace.documentservice.domain.repository.SignedDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DocumentServiceImpl implements DocumentService {

    private final DocumentTemplateRepository templateRepository;
    private final SignedDocumentRepository signedDocumentRepository;

    // FIX #4 — Her request'te üretilmez; AppConfig'deki singleton bean inject edilir
    private final KeyPair rsaKeyPair;

    @Override
    public List<DocumentTemplateResponse> findAllTemplates() {
        return templateRepository.findAll().stream()
                .map(this::toTemplateResponse)
                .toList(); // Java 16+ — Collectors.toList() değil
    }

    @Override
    @Transactional // Write işlemi — sınıf seviyesindeki readOnly=true override edilir
    public SignedDocumentResponse signDocument(SignDocumentRequest request) {
        // Step 1 — Template'i bul; yoksa TemplateNotFoundException fırlat
        DocumentTemplate template = templateRepository.findById(
                        request.templateId().longValue())
                .orElseThrow(() -> new TemplateNotFoundException(request.templateId()));

        // Step 2 — Template içeriğini müşteri bilgileriyle kişiselleştir
        String personalizedContent = template.getContent()
                .replace("{{customerName}}", request.customerName())
                .replace("{{customerId}}", request.customerId());

        // Step 3 & 4 — Singleton KeyPair ile SHA256withRSA imzala, HEX'e çevir
        String signatureHash = computeSignatureHash(personalizedContent);

        // Step 5 — SignedDocument oluştur ve kalıcı hale getir
        SignedDocument signedDocument = SignedDocument.create(
                request.templateId(),
                request.customerId(),
                request.customerName(),
                signatureHash
        );
        SignedDocument saved = signedDocumentRepository.save(signedDocument);

        // Step 6 — Response döndür
        return toSignedDocumentResponse(saved);
    }

    @Override
    public Page<SignedDocumentResponse> findSignedDocuments(int page, int size) {
        // FIX #7 — Page<> kullanılır; SELECT * + List<> anti-pattern'i ortadan kalkar
        PageRequest pageable = PageRequest.of(page, size, Sort.by("signedAt").descending());
        return signedDocumentRepository.findAll(pageable)
                .map(this::toSignedDocumentResponse);
    }

    @Override
    public SignedDocumentResponse findSignedDocumentById(Long id) {
        return signedDocumentRepository.findById(id)
                .map(this::toSignedDocumentResponse)
                .orElseThrow(() -> new DocumentNotFoundException(id));
    }

    @Override
    public byte[] generatePdf(Long id) {
        SignedDocument doc = signedDocumentRepository.findById(id)
                .orElseThrow(() -> new DocumentNotFoundException(id));

        DocumentTemplate template = templateRepository.findById(doc.getTemplateId().longValue())
                .orElseThrow(() -> new TemplateNotFoundException(doc.getTemplateId()));

        String content = template.getContent()
                .replace("{{MUSTERI_ADI}}", doc.getCustomerName())
                .replace("{{customerName}}", doc.getCustomerName())
                .replace("{{TC_NO}}",        doc.getCustomerId())
                .replace("{{customerId}}",   doc.getCustomerId())
                .replace("{{FIRMA_ADI}}",    doc.getCustomerName())
                .replace("{{VERGI_NO}}",     doc.getCustomerId())
                .replace("{{KREDI_TUTARI}}", "0")
                .replace("{{VADE}}",         "0")
                .replace("{{FAIZ}}",         "0")
                .replace("{{LIMIT}}",        "0")
                .replace("{{KART_TURU}}",    "Standart")
                .replace("{{ADRES}}",        "-");

        return buildPdfBytes(doc, template.getName(), template.getVersion(), content);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Singleton RSA KeyPair ile içeriği imzalar ve imza baytlarının
     * HEX karşılığının ilk 64 karakterini hash olarak döner.
     * <p>
     * SignatureException checked exception olduğundan try/catch zorunludur;
     * ancak iş katmanına checked exception sızdırılmaz —
     * {@link SignatureProcessingException} (RuntimeException) olarak sarmalanır.
     */
    private String computeSignatureHash(String content) {
        try {
            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(rsaKeyPair.getPrivate());
            signer.update(content.getBytes());
            byte[] signatureBytes = signer.sign();
            // İlk 64 karakter hash olarak saklanır (DB column length = 128)
            return HexFormat.of().formatHex(signatureBytes).substring(0, 64);
        } catch (SignatureException ex) {
            throw new SignatureProcessingException("SHA256withRSA signing failed", ex);
        } catch (Exception ex) {
            throw new SignatureProcessingException("Unexpected error during signing", ex);
        }
    }

    private DocumentTemplateResponse toTemplateResponse(DocumentTemplate template) {
        return new DocumentTemplateResponse(
                template.getId(),
                template.getName(),
                template.getDescription(),
                template.getVersion()
        );
    }

    private SignedDocumentResponse toSignedDocumentResponse(SignedDocument doc) {
        String documentUrl = buildDocumentUrl(doc.getId());
        return new SignedDocumentResponse(
                doc.getId(),
                doc.getTemplateId(),
                doc.getCustomerId(),
                doc.getCustomerName(),
                doc.getSignatureHash(),
                doc.getSignedAt(),
                documentUrl
        );
    }

    private String buildDocumentUrl(Long docId) {
        try {
            ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest req = attrs.getRequest();
                String base = req.getScheme() + "://" + req.getServerName()
                        + (req.getServerPort() == 80 || req.getServerPort() == 443
                            ? "" : ":" + req.getServerPort());
                return base + "/api/documents/pdf/" + docId;
            }
        } catch (Exception ignored) {}
        return "/api/documents/pdf/" + docId;
    }

    /**
     * External kütüphane gerektirmeyen minimal PDF üretici.
     * ASCII karakterleri destekler — Type1 / Helvetica font ile uyumlu.
     */
    private byte[] buildPdfBytes(SignedDocument doc, String templateName,
                                  String version, String content) {
        String safeContent  = toAscii(content);
        String safeName     = toAscii(doc.getCustomerName());
        String safeTpl      = toAscii(templateName);
        String safeDate     = doc.getSignedAt() != null
                ? doc.getSignedAt().toString().replace("T", " ").substring(0, Math.min(19, doc.getSignedAt().toString().length()))
                : "-";

        List<String> lines = new ArrayList<>();
        lines.add("AKBANK - Imzali Dokuman");
        lines.add("========================");
        lines.add("Dokuman ID  : " + doc.getId());
        lines.add("Musteri     : " + safeName);
        lines.add("TC / Musteri No: " + doc.getCustomerId());
        lines.add("Sablon      : " + safeTpl + " (" + version + ")");
        lines.add("Imza Tarihi : " + safeDate);
        lines.add("Imza Hash   : " + doc.getSignatureHash());
        lines.add("");
        lines.add("SOZLESME ICERIGI:");
        lines.add("-----------------");
        // Satır başı wrap — 80 karakter
        for (String word : safeContent.split("(?<=\\G.{80})")) {
            lines.add(word);
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            // PDF header
            write(baos, "%PDF-1.4\n");

            // Object 1: Catalog
            int obj1Offset = baos.size();
            write(baos, "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n");

            // Object 2: Pages
            int obj2Offset = baos.size();
            write(baos, "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n");

            // Build content stream
            StringBuilder cs = new StringBuilder();
            cs.append("BT\n/F1 10 Tf\n14 TL\n50 800 Td\n");
            for (String line : lines) {
                cs.append("(").append(line.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)")).append(") Tj\nT*\n");
            }
            cs.append("ET\n");
            byte[] csBytes = cs.toString().getBytes(StandardCharsets.ISO_8859_1);

            // Object 4: Content stream
            int obj4Offset = baos.size();
            write(baos, "4 0 obj\n<< /Length " + csBytes.length + " >>\nstream\n");
            baos.write(csBytes);
            write(baos, "\nendstream\nendobj\n");

            // Object 5: Font
            int obj5Offset = baos.size();
            write(baos, "5 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n");

            // Object 3: Page
            int obj3Offset = baos.size();
            write(baos, "3 0 obj\n<< /Type /Page /Parent 2 0 R "
                    + "/MediaBox [0 0 595 842] "
                    + "/Contents 4 0 R "
                    + "/Resources << /Font << /F1 5 0 R >> >> >>\nendobj\n");

            // xref
            int xrefOffset = baos.size();
            write(baos, "xref\n0 6\n");
            write(baos, "0000000000 65535 f \n");
            write(baos, String.format("%010d 00000 n \n", obj1Offset));
            write(baos, String.format("%010d 00000 n \n", obj2Offset));
            write(baos, String.format("%010d 00000 n \n", obj3Offset));
            write(baos, String.format("%010d 00000 n \n", obj4Offset));
            write(baos, String.format("%010d 00000 n \n", obj5Offset));

            write(baos, "trailer\n<< /Size 6 /Root 1 0 R >>\n");
            write(baos, "startxref\n" + xrefOffset + "\n%%EOF\n");
        } catch (Exception e) {
            throw new SignatureProcessingException("PDF generation failed", e);
        }
        return baos.toByteArray();
    }

    private void write(ByteArrayOutputStream baos, String s) throws Exception {
        baos.write(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private String toAscii(String input) {
        if (input == null) return "";
        return input
                .replace("\u00e7", "c").replace("\u00c7", "C")
                .replace("\u011f", "g").replace("\u011e", "G")
                .replace("\u0131", "i").replace("\u0130", "I")
                .replace("\u00f6", "o").replace("\u00d6", "O")
                .replace("\u015f", "s").replace("\u015e", "S")
                .replace("\u00fc", "u").replace("\u00dc", "U")
                .replaceAll("[^\\x20-\\x7E]", "?");
    }
}
