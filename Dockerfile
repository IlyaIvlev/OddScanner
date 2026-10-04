FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven \
    && mvn clean package -DskipTests

FROM eclipse-temurin:21-jre-alpine

RUN apk add --no-cache chromium \
    && chromium-browser --version

WORKDIR /app

COPY --from=builder /app/target/*.jar app.jar

# Playwright needs some dependencies
RUN apk add --no-cache \
    nss \
    freetype \
    harfbuzz \
    ca-certificates \
    ttf-freefont

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]