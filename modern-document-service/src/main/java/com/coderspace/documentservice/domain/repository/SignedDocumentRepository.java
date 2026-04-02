package com.coderspace.documentservice.domain.repository;

import com.coderspace.documentservice.domain.entity.SignedDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignedDocumentRepository extends JpaRepository<SignedDocument, Long> {

    /**
     * FIX #7 — SELECT * yerine sayfalı sorgu.
     * Tüm kayıtları çeken metodlar List<> değil Page<> döndürür.
     * Kullanım: findAllByCustomerId(id, PageRequest.of(0, 20))
     */
    Page<SignedDocument> findAllByCustomerId(String customerId, Pageable pageable);

    /**
     * Belirli bir müşteriye ait toplam imzalı belge sayısını döner.
     * COUNT sorgusu üretir — tüm kayıtları belleğe çekmez.
     */
    long countByCustomerId(String customerId);
}
