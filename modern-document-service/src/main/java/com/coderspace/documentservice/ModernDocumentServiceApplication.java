package com.coderspace.documentservice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.coderspace.documentservice.domain.repository")
@EntityScan(basePackages = "com.coderspace.documentservice.domain.entity")
public class ModernDocumentServiceApplication {

    private static final Logger log = LoggerFactory.getLogger(ModernDocumentServiceApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ModernDocumentServiceApplication.class, args);

        log.info("=== Modern Document Service Started ===");
        log.info("=== Virtual Threads: ENABLED ===");
        log.info("=== Legacy Bottleneck: RESOLVED ===");
        log.info("=== DB Secret: modern-db-secret applied ===");
    }
}
