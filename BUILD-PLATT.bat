@echo off
echo Building Platt for Paper 1.21.11 with Java 21...
where gradle >nul 2>nul
if errorlevel 1 (
    echo Gradle is not installed. Use the included GitHub Actions workflow or install Gradle 8.8+.
    exit /b 1
)
call gradle clean build
if errorlevel 1 (
    echo Build failed. Check the compiler errors above.
    exit /b 1
)
echo The plugin JAR is in build\libs\Platt-0.1.0-alpha.jar
