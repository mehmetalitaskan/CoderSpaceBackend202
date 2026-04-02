package com.coderspace.documentservice.infrastructure.service;

import com.coderspace.documentservice.application.dto.request.SignDocumentRequest;
import com.coderspace.documentservice.application.dto.response.DocumentTemplateResponse;
import com.coderspace.documentservice.application.dto.response.SignedDocumentResponse;
import com.coderspace.documentservice.application.exception.DocumentNotFoundException;
import com.coderspace.documentservice.application.exception.TemplateNotFoundException;
import com.coderspace.documentservice.domain.entity.DocumentTemplate;
import com.coderspace.documentservice.domain.entity.SignedDocument;
import com.coderspace.documentservice.domain.repository.DocumentTemplateRepository;
import com.coderspace.documentservice.domain.repository.SignedDocumentRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceImplTest {

    @Mock
    private DocumentTemplateRepository templateRepository;

    @Mock
    private SignedDocumentRepository signedDocumentRepository;

    // FIX #4 — Gerçek RSA KeyPair; @BeforeAll static ile bir kez üretilir
    private static KeyPair rsaKeyPair;

    private DocumentServiceImpl service;

    @BeforeAll
    static void initKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        rsaKeyPair = generator.generateKeyPair();
    }

    @BeforeEach
    void setUp() {
        // @RequiredArgsConstructor constructor'ı — field injection değil
        service = new DocumentServiceImpl(templateRepository, signedDocumentRepository, rsaKeyPair);
    }

    // -------------------------------------------------------------------------
    // findAllTemplates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Tüm şablonlar istendiğinde eşlenmiş DTO listesi dönmeli")
    void findAllTemplates_ShouldReturnMappedList() {
        // given
        when(templateRepository.findAll()).thenReturn(List.of(dummyTemplate(), dummyTemplate()));

        // when
        List<DocumentTemplateResponse> result = service.findAllTemplates();

        // then
        assertThat(result).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // signDocument
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Şablon mevcutsa imzalı belge oluşturulmalı ve signatureHash dolu olmalı")
    void signDocument_WhenTemplateExists_ShouldReturnResponse() {
        // given
        SignDocumentRequest request = new SignDocumentRequest(1, "12345678901", "Ali Yılmaz");

        when(templateRepository.findById(1L)).thenReturn(Optional.of(dummyTemplate()));

        SignedDocument savedDoc = mock(SignedDocument.class);
        when(savedDoc.getId()).thenReturn(1L);
        when(savedDoc.getTemplateId()).thenReturn(1);
        when(savedDoc.getCustomerId()).thenReturn("12345678901");
        when(savedDoc.getCustomerName()).thenReturn("Ali Yılmaz");
        when(savedDoc.getSignatureHash()).thenReturn("a".repeat(64));
        when(savedDoc.getSignedAt()).thenReturn(Instant.now());
        when(signedDocumentRepository.save(any())).thenReturn(savedDoc);

        // when
        SignedDocumentResponse result = service.signDocument(request);

        // then
        assertThat(result.customerId()).isEqualTo(request.customerId());
        assertThat(result.signatureHash()).isNotBlank();
    }

    @Test
    @DisplayName("Şablon bulunamazsa TemplateNotFoundException fırlatılmalı")
    void signDocument_WhenTemplateNotFound_ShouldThrowTemplateNotFoundException() {
        // given
        SignDocumentRequest request = new SignDocumentRequest(99, "12345678901", "Ali Yılmaz");
        when(templateRepository.findById(99L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.signDocument(request))
                .isInstanceOf(TemplateNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // findSignedDocumentById
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("ID ile belge bulunamazsa DocumentNotFoundException fırlatılmalı ve mesaj ID içermeli")
    void findSignedDocumentById_WhenNotFound_ShouldThrowDocumentNotFoundException() {
        // given
        when(signedDocumentRepository.findById(999L)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.findSignedDocumentById(999L))
                .isInstanceOf(DocumentNotFoundException.class)
                .hasMessageContaining("999");
    }

    // -------------------------------------------------------------------------
    // findSignedDocuments
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Sayfalı belge listesi istendiğinde totalElements sıfırdan büyük olmalı")
    void findSignedDocuments_ShouldReturnPagedResponse() {
        // given
        SignedDocument doc = mock(SignedDocument.class);
        when(doc.getId()).thenReturn(1L);
        when(doc.getTemplateId()).thenReturn(1);
        when(doc.getCustomerId()).thenReturn("12345678901");
        when(doc.getCustomerName()).thenReturn("Ali Yılmaz");
        when(doc.getSignatureHash()).thenReturn("a".repeat(64));
        when(doc.getSignedAt()).thenReturn(Instant.now());

        Page<SignedDocument> page = new PageImpl<>(List.of(doc));
        when(signedDocumentRepository.findAll(any(Pageable.class))).thenReturn(page);

        // when
        Page<SignedDocumentResponse> result = service.findSignedDocuments(0, 10);

        // then
        assertThat(result.getTotalElements()).isGreaterThan(0);
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private static DocumentTemplate dummyTemplate() {
        return DocumentTemplate.create(
                "Test Şablon",
                "Test açıklama",
                "1.0",
                "Sayın {{customerName}}, TC Kimlik No: {{customerId}} — imzalı belge."
        );
    }
}
