package com.coderspace.documentservice.application.dto.response;

public record DocumentTemplateResponse(
        Long id,
        String name,
        String description,
        String version
) {}
