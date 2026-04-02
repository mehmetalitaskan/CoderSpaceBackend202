package com.coderspace.documentservice.application.exception;

public class TemplateNotFoundException extends RuntimeException {

    private final Integer templateId;

    public TemplateNotFoundException(Integer templateId) {
        super("Document template not found with id: " + templateId);
        this.templateId = templateId;
    }

    public Integer getTemplateId() {
        return templateId;
    }
}
