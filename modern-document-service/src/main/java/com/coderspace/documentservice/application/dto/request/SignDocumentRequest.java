package com.coderspace.documentservice.application.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record SignDocumentRequest(

        @NotNull(message = "templateId boş olamaz")
        @Positive(message = "templateId pozitif bir sayı olmalıdır")
        Integer templateId,

        @NotBlank(message = "customerId boş olamaz")
        @Size(min = 11, max = 11, message = "customerId tam 11 karakter olmalıdır")
        String customerId,

        @NotBlank(message = "customerName boş olamaz")
        @Size(min = 2, max = 100, message = "customerName 2 ile 100 karakter arasında olmalıdır")
        String customerName
) {}
