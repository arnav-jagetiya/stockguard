# ==============================================================================
# STOCKGUARD — Backend Multi-Stage Production Dockerfile
# Java 21 + Spring Boot 3.4.3 + Eclipse Temurin Alpine
# ==============================================================================

# Stage 1: Build & Package
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /build

# Copy Maven wrapper and POM for dependency layer caching
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

# Copy source code and package application JAR (skipping unit/integration tests during image build)
COPY src/ src/
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Minimal Distroless / Hardened Alpine Runtime
FROM eclipse-temurin:21-jre-alpine AS runner

# Install wget for healthcheck probe
RUN apk add --no-cache wget

# Create unprivileged system user and group (Principle of Least Privilege)
RUN addgroup --system --gid 10001 stockguard && \
    adduser --system --uid 10001 --ingroup stockguard --shell /sbin/nologin stockguard

WORKDIR /app

# Copy executable jar from builder stage
COPY --from=builder --chown=stockguard:stockguard /build/target/stockguard-*.jar app.jar

# Run as non-root user
USER stockguard:stockguard

# Default container JVM ergonomics
ENV JAVA_TOOL_OPTIONS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
ENV SERVER_PORT=8080
ENV MANAGEMENT_PORT=8081

# Expose primary application port and separate management port
EXPOSE 8080
EXPOSE 8081

# Container healthcheck querying actuator liveness probe on management port
HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=3 \
  CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8081/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
