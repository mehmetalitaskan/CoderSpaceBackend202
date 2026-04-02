package com.coderspace.documentservice.presentation.exception;

import com.coderspace.documentservice.application.exception.DocumentNotFoundException;
import com.coderspace.documentservice.application.service.DocumentService;
import com.coderspace.documentservice.presentation.controller.DocumentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GlobalExceptionHandler entegrasyon testleri.
 *
 * @WebMvcTest(DocumentController.class) ile web katmanı yüklenir;
 * @RestControllerAdvice olan GlobalExceptionHandler otomatik olarak devreye girer.
 * Veritabanı bağlantısı gerekmez — DocumentService mock'lanır.
 */
@WebMvcTest(DocumentController.class)
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DocumentService documentService;

    // -------------------------------------------------------------------------
    // ErrorResponse format doğrulama
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Belge bulunamazsa standart ErrorResponse formatı eksiksiz dönmeli")
    void whenDocumentNotFound_ShouldReturnStandardErrorResponse() throws Exception {
        // given
        when(documentService.findSignedDocumentById(999L))
                .thenThrow(new DocumentNotFoundException(999L));

        // when / then
        mockMvc.perform(get("/api/documents/signed/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/documents/signed/999"));
    }

    // -------------------------------------------------------------------------
    // Bean Validation hata formatı doğrulama
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Validasyon hatası oluştuğunda 400 ve anlamlı hata mesajı dönmeli")
    void whenValidationFails_ShouldReturnFieldErrors() throws Exception {
        // given — customerId "123": @Size(min=11, max=11) ihlali
        String invalidBody = """
                {
                  "templateId": 1,
                  "customerId": "123",
                  "customerName": "Ali"
                }
                """;

        // when / then
        mockMvc.perform(post("/api/documents/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty())
                .andExpect(jsonPath("$.path").value("/api/documents/sign"));
    }
}
