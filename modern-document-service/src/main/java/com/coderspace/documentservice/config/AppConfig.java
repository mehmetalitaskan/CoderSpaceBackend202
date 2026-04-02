package com.coderspace.documentservice.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * FIX #4 — Legacy'de KeyPairGenerator her request'te çalıştırılıyordu (CPU israfı).
     * Burada RSA KeyPair uygulama başında yalnızca BİR KEZ üretilir ve
     * Spring container tarafından singleton olarak yönetilir.
     */
    @Bean
    public KeyPair rsaKeyPair() throws NoSuchAlgorithmException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair keyPair = generator.generateKeyPair();
        log.info("RSA KeyPair initialized (singleton)");
        return keyPair;
    }

    /**
     * FIX #1 — Görev başına yeni bir Virtual Thread oluşturur.
     * Platform thread havuzuna ihtiyaç duymadan milyonlarca eş zamanlı görevi destekler.
     * Service katmanında @Async ya da manuel executor inject edilmesi gerektiğinde kullanılır.
     */
    @Bean
    @Primary
    public Executor virtualThreadExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
