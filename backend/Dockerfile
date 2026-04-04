FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/enterpriseiq-0.1.0.jar app.jar
EXPOSE 8080
ENV LANG=C.UTF-8
ENV LC_ALL=C.UTF-8
ENTRYPOINT ["java", "-Dfile.encoding=UTF-8", "-Duser.language=es", "-Duser.country=ES", "-jar", "/app/app.jar"]
