package com.coderspace.documentservice.domain.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;

@Entity
@Table(name = "document_templates")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DocumentTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static DocumentTemplate create(String name, String description, String version, String content) {
        DocumentTemplate template = new DocumentTemplate();
        template.name = name;
        template.description = description;
        template.version = version;
        template.content = content;
        return template;
    }
}
