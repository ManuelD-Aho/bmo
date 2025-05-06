# Étape 1 : Build JavaFX + FXML
FROM maven:3.9.3-eclipse-temurin-17 AS build

WORKDIR /app
COPY ./client /app
COPY ./common /app/../common

RUN mvn -f /app/pom.xml clean package
WORKDIR /client

COPY --from=build /app/target/client-*.jar /client/client.jar
COPY ./client/src/main/resources /client/resources

EXPOSE 8080

CMD ["java", "-jar", "/client/client.jar"]
