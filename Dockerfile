# ── Stage 1: Build frontend ──
FROM node:20-alpine AS frontend-builder
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json* ./
RUN npm ci --prefer-offline
COPY frontend/ ./
RUN npm run build:prod

# ── Stage 2: Build backend (embed frontend into static resources) ──
FROM maven:3.9-eclipse-temurin-21-alpine AS backend-builder
WORKDIR /src

COPY pom.xml ./
COPY backend/pom.xml backend/
COPY backend/local-repo/ backend/local-repo/
RUN mvn -f backend/pom.xml dependency:go-offline -B -q

COPY backend/ backend/
COPY --from=frontend-builder /src/dist/ backend/src/main/resources/static/

RUN mvn -f backend/pom.xml package -DskipTests -B -q

# ── Stage 3: Runtime ──
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=backend-builder /src/backend/target/majo-backend.jar ./majo.jar

RUN mkdir -p /app/data

EXPOSE 18789

ENTRYPOINT ["java", "-jar", "/app/majo.jar"]
