# =============================================================================
# PolicyGuard - one container: Spring Boot API + the React UI as static files.
# For any container host with >= 1 GB RAM (the in-JVM embedding model needs ~300 MB of heap;
# measured: a 512 MB free tier runs out of memory). Configure via env vars: DATABASE_URL,
# GROQ_API_KEY, ADMIN_KEY, CORS_ORIGINS. The host can override PORT.
# =============================================================================

# ---------- Stage 1: build the React frontend ----------
FROM node:20-alpine AS frontend
WORKDIR /ui
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- Stage 2: build the Spring Boot jar with the UI bundled in ----------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src/ src/
COPY --from=frontend /ui/dist/ src/main/resources/static/
RUN mvn -B -q package -DskipTests

# ---------- Stage 3: small runtime image ----------
FROM eclipse-temurin:21-jre
WORKDIR /app
# Bake the embedding model into the image so a cold start never has to download it.
ADD https://github.com/spring-projects/spring-ai/raw/main/models/spring-ai-transformers/src/main/resources/onnx/all-MiniLM-L6-v2/model.onnx /app/models/model.onnx
COPY --from=backend /build/target/policyguard.jar /app/app.jar
# Run as a non-root user.
RUN chown -R 1000:1000 /app
USER 1000
ENV PORT=8080 \
    ONNX_MODEL_URI=file:/app/models/model.onnx \
    ONNX_CACHE_DIR=/tmp/policyguard-onnx \
    OLLAMA_ENABLED=false \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+UseG1GC"
EXPOSE 8080
CMD ["java", "-jar", "/app/app.jar"]
