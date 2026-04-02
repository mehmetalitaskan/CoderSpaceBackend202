package com.coderspace.documentservice.domain.repository;

import com.coderspace.documentservice.domain.entity.DocumentTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentTemplateRepository extends JpaRepository<DocumentTemplate, Long> {

    /**
     * Şablon adına göre tekil kayıt döner.
     * Service katmanında TemplateNotFoundException fırlatmak için kullanılır.
     */
    Optional<DocumentTemplate> findByName(String name);

    /**
     * Aynı isim + versiyon kombinasyonunun var olup olmadığını kontrol eder.
     * Mükerrer şablon oluşturmayı önlemek için kullanılır.
     */
    boolean existsByNameAndVersion(String name, String version);
}
