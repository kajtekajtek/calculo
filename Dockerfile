# syntax=docker/dockerfile:1
# Only multi-architecture base images, so the build also works on ARM.

FROM node:22 AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=frontend /frontend/dist ./src/main/resources/static
RUN mvn -B -q -DskipTests package \
    && cp target/calculo-*.jar /backend/app.jar

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home calculo
WORKDIR /app
COPY --from=backend /backend/app.jar app.jar
USER calculo
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
