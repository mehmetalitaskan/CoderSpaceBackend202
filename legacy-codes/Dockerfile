# ─────────────────────────────────────────────────────────────────
#  AKBANK — Legacy Document Service
#  Java 8  |  Bloklayan (Blocking) Mimari  |  "The Legacy Rescue"
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:8-jdk-alpine

LABEL maintainer="Akbank Legacy Team"
LABEL description="Java 8 blocking REST service — demonstration of legacy pain points"

WORKDIR /app

# PostgreSQL JDBC driver indir (harici kutüphane — legacy dependency yönetimi yok!)
RUN wget -q https://jdbc.postgresql.org/download/postgresql-42.7.3.jar -O postgresql.jar

# Kaynak dosyayı kopyala
COPY LegacyDocumentService.java .

# Derleme — JDBC driver classpath'e ekleniyor
RUN javac -cp postgresql.jar LegacyDocumentService.java

# 8080 portunu dışarı aç
EXPOSE 8080

# Servisi başlat
CMD ["java", "-Xmx256m", "-Xms64m", "-Djdk.tls.client.protocols=TLSv1.2", "-Djdk.tls.disabledAlgorithms=", "-cp", ".:postgresql.jar", "LegacyDocumentService"]
