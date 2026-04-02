# ─────────────────────────────────────────────────────────────────
#  AKBANK — Legacy Document Service
#  Java 8  |  Bloklayan (Blocking) Mimari  |  "The Legacy Rescue"
# ─────────────────────────────────────────────────────────────────
FROM eclipse-temurin:8-jdk-alpine

LABEL maintainer="Akbank Legacy Team"
LABEL description="Java 8 blocking REST service — demonstration of legacy pain points"

WORKDIR /app

# Kaynak dosyayı kopyala
COPY LegacyDocumentService.java .

# Derleme — harici kütüphane yok, sadece JDK
RUN javac LegacyDocumentService.java

# 8080 portunu dışarı aç
EXPOSE 8080

# Servisi başlat
CMD ["java", "-Xmx256m", "-Xms64m", "LegacyDocumentService"]
