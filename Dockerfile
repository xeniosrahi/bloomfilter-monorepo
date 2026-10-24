# Stage 1: Multi-module Gradle build
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /workspace

COPY . .
RUN chmod +x ./gradlew && ./gradlew --no-daemon clean :bloomfilter-sync-service:bootJar -x test

# Stage 2: Hardened production runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

RUN addgroup -S bloomgroup && adduser -S bloomuser -G bloomgroup \
    && mkdir -p /certs \
    && chown -R bloomuser:bloomgroup /app /certs

COPY --from=builder \
  /workspace/bloomfilter-sync-service/build/libs/bloomfilter-sync-service-1.0.0.jar \
  /app/app.jar

USER bloomuser
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-XX:+UseZGC", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
