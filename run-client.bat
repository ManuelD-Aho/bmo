@echo off
setlocal

REM Configuration des chemins
set JAVA_HOME=C:\Program Files\Java\jdk-17
set JAVAFX_HOME=D:\javafx-sdk-24
set APP_HOME=%~dp0

REM Vérification de l'existence des répertoires
if not exist "%JAVA_HOME%" (
    echo ERREUR: Java JDK introuvable: %JAVA_HOME%
    echo Veuillez modifier le chemin JAVA_HOME dans ce script.
    pause
    exit /b 1
)

if not exist "%JAVAFX_HOME%" (
    echo ERREUR: JavaFX SDK introuvable: %JAVAFX_HOME%
    echo Veuillez modifier le chemin JAVAFX_HOME dans ce script.
    pause
    exit /b 1
)

REM Configuration des classpath
set CLASSPATH=%APP_HOME%\client\target\bmo-client-1.0.0.jar
set CLASSPATH=%CLASSPATH%;%APP_HOME%\common\target\bmo-common-1.0.0.jar
set CLASSPATH=%CLASSPATH%;%APP_HOME%\client\target\libs\*

REM Configuration des modules JavaFX
set JAVAFX_MODULES=javafx.controls,javafx.fxml,javafx.web,javafx.media,javafx.graphics

REM Lancer l'application client
echo Démarrage de BMO Client...
"%JAVA_HOME%\bin\java" ^
    --module-path "%JAVAFX_HOME%\lib" ^
    --add-modules %JAVAFX_MODULES% ^
    -cp "%CLASSPATH%" ^
    -Djavafx.verbose=true ^
    -Dprism.verbose=true ^
    com.bmo.client.MainApp

if %ERRORLEVEL% neq 0 (
    echo ERREUR: L'exécution a échoué avec le code %ERRORLEVEL%
    pause
)

endlocal