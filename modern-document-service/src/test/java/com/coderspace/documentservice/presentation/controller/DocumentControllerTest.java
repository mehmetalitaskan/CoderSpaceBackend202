package com.coderspace.documentservice.presentation.controller;

import com.coderspace.documentservice.application.dto.response.DocumentTemplateResponse;
import com.coderspace.documentservice.application.dto.response.SignedDocumentResponse;
import com.coderspace.documentservice.application.exception.DocumentNotFoundException;
import com.coderspace.documentservice.application.exception.TemplateNotFoundException;
import com.coderspace.documentservice.application.service.DocumentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DocumentController.class)
class DocumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    // -------------------------------------------------------------------------
    // GET /api/templates
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/templates — 200 OK ve dolu liste dönmeli")
    void getTemplates_ShouldReturn200() throws Exception {
        // given
        DocumentTemplateResponse dto =
                new DocumentTemplateResponse(1L, "Test Şablon", "Açıklama", "1.0");
        when(documentService.findAllTemplates()).thenReturn(List.of(dto));

        // when / then
        mockMvc.perform(get("/api/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // POST /api/documents/sign
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /api/documents/sign — geçerli istek ile 201 Created dönmeli")
    void signDocument_WithValidRequest_ShouldReturn201() throws Exception {
        // given
        SignedDocumentResponse response = new SignedDocumentResponse(
                1L, 1, "12345678901", "Ali Yılmaz", "a".repeat(64), Instant.now(),
                "/api/documents/pdf/1");
        when(documentService.signDocument(any())).thenReturn(response);

        String requestBody = """
                {
                  "templateId": 1,
                  "customerId": "12345678901",
                  "customerName": "Ali Yılmaz"
                }
                """;

        // when / then
        mockMvc.perform(post("/api/documents/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.signatureHash").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/documents/sign — customerId 11 karakter değilse 400 Bad Request dönmeli")
    void signDocument_WithInvalidCustomerId_ShouldReturn400() throws Exception {
        // given — "123" geçersiz: @Size(min=11, max=11) ihlali
        String requestBody = """
                {
                  "templateId": 1,
                  "customerId": "123",
                  "customerName": "Ali Yılmaz"
                }
                """;

        // when / then
        mockMvc.perform(post("/api/documents/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    @DisplayName("POST /api/documents/sign — şablon bulunamazsa 404 Not Found dönmeli")
    void signDocument_WhenTemplateNotFound_ShouldReturn404() throws Exception {
        // given
        when(documentService.signDocument(any()))
                .thenThrow(new TemplateNotFoundException(99));

        String requestBody = """
                {
                  "templateId": 99,
                  "customerId": "12345678901",
                  "customerName": "Ali Yılmaz"
                }
                """;

        // when / then
        mockMvc.perform(post("/api/documents/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    // -------------------------------------------------------------------------
    // GET /api/documents/signed/{id}
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /api/documents/signed/{id} — belge bulunamazsa 404 Not Found dönmeli")
    void getSignedDocumentById_WhenNotFound_ShouldReturn404() throws Exception {
        // given
        when(documentService.findSignedDocumentById(999L))
                .thenThrow(new DocumentNotFoundException(999L));

        // when / then
        mockMvc.perform(get("/api/documents/signed/999"))
                .andExpect(status().isNotFound());
    }
}
