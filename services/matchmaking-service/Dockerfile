FROM maven:3.9.12-eclipse-temurin-21@sha256:c3c9d3ac4ce8431a3995c0318b8d390f448e693dd4fabc16e9b68d2e1f3d7b46 AS build
WORKDIR /workspace
COPY pom.xml pom.xml
COPY contracts/pom.xml contracts/pom.xml
COPY services/identity-service/pom.xml services/identity-service/pom.xml
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/player-service/pom.xml services/player-service/pom.xml
COPY services/matchmaking-service/pom.xml services/matchmaking-service/pom.xml
COPY services/match-service/pom.xml services/match-service/pom.xml
RUN mvn -B -ntp -pl services/matchmaking-service -am dependency:go-offline
COPY contracts/src contracts/src
COPY services/matchmaking-service/src services/matchmaking-service/src
RUN mvn -B -ntp -pl services/matchmaking-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
RUN apk upgrade --no-cache && addgroup -S pinkward && adduser -S -G pinkward pinkward
WORKDIR /app
COPY --from=build /workspace/services/matchmaking-service/target/matchmaking-service-*.jar app.jar
USER pinkward
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
