# =============================================================================
# Stage 1: Multi-Module Maven Build
# =============================================================================
FROM maven:3.9.8-eclipse-temurin-21-alpine AS builder
WORKDIR /workspace

COPY pom.xml ./
COPY bloomfilter-core/pom.xml bloomfilter-core/pom.xml
COPY bloomfilter-redis-sdk/pom.xml bloomfilter-redis-sdk/pom.xml
COPY bloomfilter-sync-service/pom.xml bloomfilter-sync-service/pom.xml

COPY bloomfilter-core/src bloomfilter-core/src
COPY bloomfilter-redis-sdk/src bloomfilter-redis-sdk/src
COPY bloomfilter-sync-service/src bloomfilter-sync-service/src

RUN mvn clean package -DskipTests -B

# =============================================================================
# Stage 2: Hardened Production Runtime (Eclipse Temurin 21 JRE Alpine)
# =============================================================================
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S bloomgroup && adduser -S bloomuser -G bloomgroup
RUN mkdir -p /certs && chown -R bloomuser:bloomgroup /app /certs

COPY --from=builder /workspace/bloomfilter-sync-service/target/bloomfilter-sync-service-1.0.0.jar /app/app.jar

USER bloomuser
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
