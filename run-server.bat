@echo off
cd server

echo Building server...
mvn clean install

echo Starting BMO Server...
java -jar target/server-1.0-SNAPSHOT.jar --config=src/main/resources/application.properties

pause
