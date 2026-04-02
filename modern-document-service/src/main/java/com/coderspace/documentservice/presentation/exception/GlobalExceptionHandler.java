package com.coderspace.documentservice.presentation.exception;

import com.coderspace.documentservice.application.exception.DocumentNotFoundException;
import com.coderspace.documentservice.application.exception.SignatureProcessingException;
import com.coderspace.documentservice.application.exception.TemplateNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 404 — İmzalı belge bulunamadı.
     */
    @ExceptionHandler(DocumentNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleDocumentNotFound(
            DocumentNotFoundException ex, HttpServletRequest request) {
        log.warn("Document not found: id={}", ex.getDocumentId());
        return buildResponse(HttpStatus.NOT_FOUND, "Document Not Found", ex.getMessage(), request);
    }

    /**
     * 404 — Şablon bulunamadı.
     */
    @ExceptionHandler(TemplateNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTemplateNotFound(
            TemplateNotFoundException ex, HttpServletRequest request) {
        log.warn("Template not found: id={}", ex.getTemplateId());
        return buildResponse(HttpStatus.NOT_FOUND, "Template Not Found", ex.getMessage(), request);
    }

    /**
     * 500 — RSA imzalama başarısız.
     * Kök neden (cause) log'lanır; client'a iç detay sızdırılmaz.
     */
    @ExceptionHandler(SignatureProcessingException.class)
    public ResponseEntity<ErrorResponse> handleSignatureProcessing(
            SignatureProcessingException ex, HttpServletRequest request) {
        log.error("Signature processing failed: reason={}", ex.getReason(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Signature Processing Error",
                ex.getMessage(), request);
    }

    /**
     * 400 — @Valid ile tetiklenen Bean Validation hataları.
     * Her field hatası "field: mesaj" formatında birleştirilerek döner.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining(", "));
        log.warn("Validation failed: path={} errors={}", request.getRequestURI(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation Failed", message, request);
    }

    /**
     * 400 — @Validated + @RequestParam/@PathVariable constraint ihlalleri.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
        log.warn("Constraint violation: path={} errors={}", request.getRequestURI(), message);
        return buildResponse(HttpStatus.BAD_REQUEST, "Constraint Violation", message, request);
    }

    /**
     * 500 — Yakalanmayan tüm exception'lar için genel fallback.
     * Gerçek hata log'lanır; client'a yalnızca genel mesaj döner.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex, HttpServletRequest request) {
        log.error("Unexpected error: path={}", request.getRequestURI(), ex);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error",
                "An unexpected error occurred. Please try again later.", request);
    }

    // -------------------------------------------------------------------------
    // Private helper
    // -------------------------------------------------------------------------

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status, String error, String message, HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(),
                status.value(),
                error,
                message,
                request.getRequestURI()
        );
        return ResponseEntity.status(status).body(body);
    }
}
