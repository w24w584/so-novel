@echo off
setlocal

set "PROJECT_DIR=%~dp0.."
for %%I in ("%PROJECT_DIR%") do set "PROJECT_DIR=%%~fI"
set "JRE_NAME=jre-21.0.8+9-windows_x64.zip"
set "JRE_DIR=jdk-21.0.8+9-jre"
set "JRE_PATH=%PROJECT_DIR%\bundle\%JRE_NAME%"
set "TARGET_DIR=%PROJECT_DIR%\target\SoNovel"
set "DIST_DIR=%PROJECT_DIR%\dist"
set "DIST_FILE=%DIST_DIR%\sonovel-windows.tar.gz"
set "DOWNLOAD_URL=https://github.com/adoptium/temurin21-binaries/releases/download/jdk-21.0.8+9/OpenJDK21U-jre_x64_windows_hotspot_21.0.8_9.zip"

echo Building Windows x64 package...

if not exist "%PROJECT_DIR%\pom.xml" (
    echo ERROR: Could not find pom.xml under %PROJECT_DIR%
    exit /b 1
)

REM --- Locate JDK 21 ---
REM If auto-detection fails, hardcode your JDK 21 path by uncommenting the next line:
REM set "JDK21_HOME=E:\Study\jdk-21.0.12.1"

if not defined JDK21_HOME (
    for /f "usebackq delims=" %%I in (`powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%\bin\find-jdk21.ps1" 2^>nul`) do set "JDK21_HOME=%%I"
)

if not defined JDK21_HOME (
    echo ERROR: JDK 21 not found. Set JDK21_HOME in this script, or JAVA_HOME to a JDK 21 installation.
    exit /b 1
)

echo Using JDK: %JDK21_HOME%
set "JAVA_HOME=%JDK21_HOME%"

if not exist "%JRE_PATH%" (
    echo Downloading %JRE_NAME%...
    curl.exe --retry 3 -L -o "%JRE_PATH%" "%DOWNLOAD_URL%"
    if errorlevel 1 (
        echo ERROR: Failed to download the bundled JRE.
        exit /b 1
    )
)

set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d "%PROJECT_DIR%"
call mvn.cmd clean package -Pwindows-x64 "-Dmaven.test.skip=true" "-DjrePath=runtime"
if errorlevel 1 (
    echo ERROR: Maven build failed.
    exit /b 1
)

copy /y "%PROJECT_DIR%\target\app-jar-with-dependencies.jar" "%TARGET_DIR%\app.jar" >nul
if errorlevel 1 goto :copy_error
copy /y "%JRE_PATH%" "%TARGET_DIR%\%JRE_NAME%" >nul
if errorlevel 1 goto :copy_error
copy /y "%PROJECT_DIR%\bundle\sonovel.l4j.ini" "%TARGET_DIR%\" >nul
if errorlevel 1 goto :copy_error
copy /y "%PROJECT_DIR%\bundle\readme.txt" "%TARGET_DIR%\" >nul
if errorlevel 1 goto :copy_error
robocopy "%PROJECT_DIR%\bundle\rules" "%TARGET_DIR%\rules" /E /NFL /NDL /NJH /NJS /NC /NS >nul
if errorlevel 8 goto :copy_error

if exist "%DIST_DIR%\SoNovel\config.ini" (
    copy /y "%DIST_DIR%\SoNovel\config.ini" "%TARGET_DIR%\config.ini" >nul
) else (
    copy /y "%PROJECT_DIR%\bundle\config.ini" "%TARGET_DIR%\config.ini" >nul
)
if errorlevel 1 goto :copy_error

set "JRE_ARCHIVE=%JRE_PATH%"
set "PACKAGE_DIR=%TARGET_DIR%"
powershell.exe -NoProfile -Command "Expand-Archive -LiteralPath $env:JRE_ARCHIVE -DestinationPath $env:PACKAGE_DIR -Force"
if errorlevel 1 (
    echo ERROR: Failed to extract the bundled JRE.
    exit /b 1
)

if not exist "%TARGET_DIR%\%JRE_DIR%\bin\java.exe" (
    echo ERROR: The extracted JRE folder was not found.
    exit /b 1
)
if exist "%TARGET_DIR%\runtime" rmdir /s /q "%TARGET_DIR%\runtime"
ren "%TARGET_DIR%\%JRE_DIR%" runtime
if errorlevel 1 (
    echo ERROR: Could not rename the bundled JRE folder.
    exit /b 1
)
del /q "%TARGET_DIR%\%JRE_NAME%"

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"
tar.exe -czf "%DIST_FILE%" -C "%PROJECT_DIR%\target" SoNovel
if errorlevel 1 (
    echo ERROR: Failed to create %DIST_FILE%
    exit /b 1
)

echo Build complete: %DIST_FILE%
exit /b 0

:copy_error
echo ERROR: Failed to prepare the package files.
exit /b 1