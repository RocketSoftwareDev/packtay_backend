FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY auth-svc/pom.xml ./auth-svc/pom.xml
COPY business-svc/pom.xml ./business-svc/pom.xml
COPY auth-svc/src ./auth-svc/src

RUN mvn --batch-mode --no-transfer-progress -pl auth-svc -am package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/auth-svc/target/auth-svc-0.1.0-SNAPSHOT.jar app.jar

EXPOSE 8081
ENTRYPOINT ["/bin/sh", "-c", "exec java -Dserver.port=${PORT:-8081} -jar /app/app.jar"]

