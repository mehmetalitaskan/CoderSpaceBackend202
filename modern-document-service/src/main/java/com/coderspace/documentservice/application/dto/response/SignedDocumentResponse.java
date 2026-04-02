package com.coderspace.documentservice.application.dto.response;

import java.time.Instant;

public record SignedDocumentResponse(
        Long id,
        Integer templateId,
        String customerId,
        String customerName,
        String signatureHash,
        Instant signedAt
) {}
