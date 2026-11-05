FROM node:22-alpine AS ui
WORKDIR /workspace
COPY package.json package-lock.json ./
RUN npm ci
COPY src/main/frontend src/main/frontend
COPY src/main/resources/templates src/main/resources/templates
RUN npm run build:css

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /workspace
COPY pom.xml ./
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
COPY --from=ui /workspace/src/main/resources/static/css/app.css src/main/resources/static/css/app.css
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /workspace/target/regaSync-0.0.1-SNAPSHOT.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
