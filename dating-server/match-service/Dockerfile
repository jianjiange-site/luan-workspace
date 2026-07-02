# Multi-stage Dockerfile for match-service
# Stage 1: Build with Maven
FROM maven:3.9-eclipse-temurin-21-alpine AS builder
WORKDIR /build

# Cache dependencies layer
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline -DskipTests || true

# Build the service
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests -q

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

ENV TZ=UTC
ENV JAVA_OPTS="-Xms256m -Xmx512m -Duser.timezone=UTC"

COPY --from=builder /build/target/*.jar app.jar

EXPOSE 8080 9090

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
