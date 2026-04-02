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

import java.security.KeyPair;
import java.security.Signature;
import java.security.SignatureException;
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
        return new SignedDocumentResponse(
                doc.getId(),
                doc.getTemplateId(),
                doc.getCustomerId(),
                doc.getCustomerName(),
                doc.getSignatureHash(),
                doc.getSignedAt()
        );
    }
}
