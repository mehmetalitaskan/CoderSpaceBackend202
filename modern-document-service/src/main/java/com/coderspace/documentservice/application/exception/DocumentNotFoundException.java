package com.coderspace.documentservice.application.exception;

public class DocumentNotFoundException extends RuntimeException {

    private final Long documentId;

    public DocumentNotFoundException(Long documentId) {
        super("Signed document not found with id: " + documentId);
        this.documentId = documentId;
    }

    public Long getDocumentId() {
        return documentId;
    }
}
