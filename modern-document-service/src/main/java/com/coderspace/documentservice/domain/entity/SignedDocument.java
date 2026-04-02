package com.coderspace.documentservice.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "signed_documents")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignedDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Integer templateId;

    @Column(nullable = false, length = 20)
    private String customerId;

    @Column(nullable = false, length = 100)
    private String customerName;

    @Column(nullable = false, length = 128, columnDefinition = "varchar(128) default 'LEGACY_NO_SIGNATURE'")
    private String signatureHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant signedAt;

    public static SignedDocument create(Integer templateId, String customerId,
                                        String customerName, String signatureHash) {
        SignedDocument doc = new SignedDocument();
        doc.templateId = templateId;
        doc.customerId = customerId;
        doc.customerName = customerName;
        doc.signatureHash = signatureHash;
        return doc;
    }
}
