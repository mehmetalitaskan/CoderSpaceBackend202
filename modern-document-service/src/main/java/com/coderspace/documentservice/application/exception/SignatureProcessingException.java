package com.coderspace.documentservice.application.exception;

public class SignatureProcessingException extends RuntimeException {

    private final String reason;

    public SignatureProcessingException(String reason, Throwable cause) {
        super("Document signature processing failed: " + reason, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }
}
