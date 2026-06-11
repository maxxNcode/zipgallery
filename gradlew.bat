@rem ZipGallery Gradle wrapper
@if "%DEBUG%"=="" @echo off
setlocal enabledelayedexpansion

set DIRNAME=%~dp0
set WRAPPER_JAR=%DIRNAME%gradle\wrapper\gradle-wrapper.jar

if not exist "%WRAPPER_JAR%" (
    echo ERROR: gradle-wrapper.jar not found. Run build.ps1 first.
    exit /b 1
)

if not "%JAVA_HOME%"=="" (
    set JAVA_EXE="%JAVA_HOME%\bin\java.exe"
) else (
    where java >nul 2>nul
    if !errorlevel! equ 0 (
        set JAVA_EXE=java
    ) else (
        echo ERROR: Java not found. Install JDK 17+.
        exit /b 1
    )
)

%JAVA_EXE% -Dorg.gradle.appname=gradlew -classpath "%WRAPPER_JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
