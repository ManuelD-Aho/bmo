# Étape 1 : Compilation multi-modules avec Maven
FROM maven:3.9.3-eclipse-temurin-17 AS build

WORKDIR /build

# Copier tout le projet pour compilation Maven multi-modules
COPY ./pom.xml .
COPY ./common ./common
COPY ./common/pom.xml ./common/pom.xml
COPY ./server ./server
COPY ./server/pom.xml ./server/pom.xml
COPY ./client ./client
COPY ./client/pom.xml ./client/pom.xml

# Compilation de tous les modules (skip tests)
RUN mvn clean install -DskipTests

# Étape 2 : Image exécutable serveur
FROM eclipse-temurin:17-jdk

WORKDIR /server

COPY --from=build /build/server/target/server-1.0-SNAPSHOT.jar ./server.jar
COPY ./server/src/main/resources/application.properties ./application.properties

EXPOSE 12345

CMD ["java", "-jar", "server.jar", "--config=application.properties"]
