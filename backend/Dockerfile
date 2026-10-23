# Один Dockerfile на два сервиса: target api (JRE) и target runner (JDK — нужен javac).
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY core/pom.xml core/
COPY analysis/pom.xml analysis/
COPY runner/pom.xml runner/
COPY api/pom.xml api/
RUN mvn -B -q dependency:go-offline -DexcludeGroupIds=io.blueprints || true
COPY . .
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre AS api
WORKDIR /app
COPY --from=build /src/api/target/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

FROM eclipse-temurin:21-jdk AS runner
RUN useradd --create-home --uid 10001 sandbox
WORKDIR /app
COPY --from=build /src/runner/target/app.jar app.jar
USER sandbox
EXPOSE 8090
ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp", "-jar", "app.jar"]
