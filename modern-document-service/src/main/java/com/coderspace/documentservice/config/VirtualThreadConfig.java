package com.coderspace.documentservice.config;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.ProtocolHandler;
import org.springframework.boot.web.embedded.tomcat.TomcatProtocolHandlerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;

@Configuration
public class VirtualThreadConfig {

    /**
     * FIX #1 — Legacy servis sabit büyüklükte bir thread havuzu kullanıyordu:
     * {@code Executors.newFixedThreadPool(10)} → maksimum 10 eş zamanlı istek.
     * <p>
     * Bu yapılandırma Tomcat'in request executor'ını tamamen değiştirir.
     * Her HTTP isteği artık ayrı bir Virtual Thread üzerinde çalışır;
     * JVM milyonlarca Virtual Thread'i çok düşük bellek maliyetiyle yönetebilir.
     * MAX_THREADS=10 sınırı burada tamamen ortadan kalkmaktadır.
     * <p>
     * Gereksinim: Java 21+ (Project Loom — GA), Spring Boot 3.2+
     */
    @Bean
    public TomcatProtocolHandlerCustomizer<?> virtualThreadCustomizer() {
        return (ProtocolHandler protocolHandler) ->
                protocolHandler.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    }
}
