# =============================================================================
# Stage 1 — Builder
# Maven bağımlılıkları önce ayrı katmanda cache'lenir; sadece kaynak kod
# değiştiğinde Maven tekrar çalışır — build süresi önemli ölçüde kısalır.
# maven:3.9-eclipse-temurin-21-alpine → mvnw wrapper dosyalarına gerek yok.
# =============================================================================
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# Bağımlılık katmanı: pom.xml ayrı COPY edilir.
# Bu katman, yalnızca pom.xml değiştiğinde yeniden indirilir.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Kaynak kodu kopyala ve jar üret
COPY src/ src/
RUN mvn package -DskipTests -q

# =============================================================================
# Stage 2 — Runtime
# JDK değil JRE kullanılır — image boyutu ~200MB küçülür.
# Non-root user ile çalıştırılır — container escape riski azalır.
# =============================================================================
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Security best practice: root olmayan kullanıcı oluştur
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Builder stage'inden yalnızca derlenmiş jar kopyalanır
COPY --from=builder /build/target/modern-document-service-1.0.0-MODERN.jar app.jar

# Jar dosyasının sahibini appuser yap
RUN chown appuser:appgroup app.jar

EXPOSE 8080

# Container kaynak limitlerini otomatik algılar (Kubernetes resources.limits ile uyumlu).
# MaxRAMPercentage=75.0 → 512Mi limit varsa JVM heap ~384Mi kullanır.
# Virtual Threads Java 21 GA'da --enable-preview GEREKTİRMEZ.
ENV JAVA_OPTS="-XX:+UseContainerSupport \
               -XX:MaxRAMPercentage=75.0 \
               -XX:+ExitOnOutOfMemoryError \
               -Djava.security.egd=file:/dev/./urandom"

# Actuator health endpoint'i üzerinden container sağlığı kontrol edilir.
# wget, alpine imajında curl'den daha hafiftir.
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

# Root yerine appuser ile çalıştır
USER appuser

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
