@echo off
setlocal
set GRADLE_VERSION=8.14.4
set GRADLE_HOME=%USERPROFILE%\.gradle\finvault\gradle-%GRADLE_VERSION%
if exist "%GRADLE_HOME%\bin\gradle.bat" goto run
set DOWNLOAD_DIR=%USERPROFILE%\.gradle\finvault
if not exist "%DOWNLOAD_DIR%" mkdir "%DOWNLOAD_DIR%"
set ZIP_FILE=%DOWNLOAD_DIR%\gradle-%GRADLE_VERSION%-bin.zip
echo Downloading Gradle %GRADLE_VERSION% for FinVault...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_FILE%'"
if errorlevel 1 exit /b 1
powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%ZIP_FILE%' -DestinationPath '%DOWNLOAD_DIR%' -Force"
if errorlevel 1 exit /b 1
:run
call "%GRADLE_HOME%\bin\gradle.bat" %*
endlocal
