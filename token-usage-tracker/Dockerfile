# syntax=docker/dockerfile:1

# --- Build stage: full JDK + Maven wrapper -----------------------------------
FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /src

# Resolve dependencies first so source edits reuse this layer
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests \
    && cp target/token-usage-tracker-*.jar /src/app.jar

# --- Runtime stage: JRE only, unprivileged user --------------------------------
FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /src/app.jar app.jar
USER app

EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
    CMD wget -qO- http://localhost:8080/actuator/health > /dev/null || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
