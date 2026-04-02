package com.coderspace.documentservice.application.service;

import com.coderspace.documentservice.application.dto.request.SignDocumentRequest;
import com.coderspace.documentservice.application.dto.response.DocumentTemplateResponse;
import com.coderspace.documentservice.application.dto.response.SignedDocumentResponse;
import org.springframework.data.domain.Page;

import java.util.List;

public interface DocumentService {

    List<DocumentTemplateResponse> findAllTemplates();

    SignedDocumentResponse signDocument(SignDocumentRequest request);

    /**
     * FIX #7 — Tüm kayıtları döndürmek yerine sayfalı sorgu kullanılır.
     * Legacy'deki SELECT * + List<> anti-pattern'i burada Page<> ile çözülür.
     */
    Page<SignedDocumentResponse> findSignedDocuments(int page, int size);

    SignedDocumentResponse findSignedDocumentById(Long id);
}
