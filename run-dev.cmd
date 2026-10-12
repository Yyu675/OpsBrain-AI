@echo off
setlocal
rem OpsBrain AI Windows dev launcher
rem Start dependencies first: docker compose -f docker-compose.dev.yml up -d

cd /d "%~dp0"
if not exist ".env" (
  echo [ERROR] .env not found. Copy .env.example to .env first.
  exit /b 1
)

rem JDK selection: JAVA21_HOME wins; else this machine's known jdk-21 install;
rem else fail with instructions instead of a confusing runtime error.
if not "%JAVA21_HOME%"=="" (
  set "JAVA_HOME=%JAVA21_HOME%"
) else if exist "D:\JAVA\JDK\jdk-21\bin\java.exe" (
  set "JAVA_HOME=D:\JAVA\JDK\jdk-21"
)
if "%MAVEN_OPTS%"=="" set "MAVEN_OPTS=-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m"
set "SPRING_PROFILES_ACTIVE=dev"

echo [INFO] Starting OpsBrain AI backend with bounded JVM memory...
echo [INFO] JAVA_HOME=%JAVA_HOME%
set "JAVA_VER="
for /f "tokens=2 delims==" %%v in ('findstr /b "JAVA_VERSION=" "%JAVA_HOME%\release" 2^>nul') do set "JAVA_VER=%%~v"
if not "%JAVA_VER:~0,2%"=="21" (
  echo [ERROR] JAVA_HOME=%JAVA_HOME% is not JDK 21 ^(detected: "%JAVA_VER%"^).
  echo         Set JAVA21_HOME to a JDK 21 install directory ^(e.g. set JAVA21_HOME=C:\path\to\jdk-21^).
  exit /b 1
)
where mvn >nul 2>&1
if errorlevel 1 (
  echo [ERROR] Maven was not found on PATH.
  exit /b 1
)
call mvn -B -ntp spring-boot:run -Dspring-boot.run.profiles=dev %*
exit /b %ERRORLEVEL%
