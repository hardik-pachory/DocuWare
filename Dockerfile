FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
RUN groupadd --system docuware && useradd --system --gid docuware docuware
WORKDIR /app
RUN mkdir -p /app/data/documents && chown -R docuware:docuware /app
COPY --from=build /build/target/docuware-0.1.0.jar /app/app.jar
USER docuware
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
