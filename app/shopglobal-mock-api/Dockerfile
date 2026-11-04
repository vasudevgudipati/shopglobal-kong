ARG BUILD_ENV=uat
FROM maven:3.9-eclipse-temurin-17 AS build
ARG BUILD_ENV
WORKDIR /src
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests -P${BUILD_ENV}

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/target/shopglobal-mock-api-*.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java","-jar","/app/app.jar"]
