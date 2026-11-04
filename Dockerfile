FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:17-jre-alpine
RUN addgroup -S campusflow && adduser -S campusflow -G campusflow
WORKDIR /app
COPY --from=build --chown=campusflow:campusflow /workspace/target/campusflow-1.0.0.jar app.jar
USER campusflow
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
