FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B verify
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S devpilot && adduser -S devpilot -G devpilot
WORKDIR /app
COPY --from=build /build/target/devpilot-0.1.0.jar app.jar
USER devpilot
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","app.jar"]
