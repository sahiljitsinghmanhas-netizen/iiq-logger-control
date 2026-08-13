@echo off
setlocal

REM ---- Config -----------------------------------------------------------
REM Both paths can be overridden from the environment, e.g.
REM   set IIQ_LIB=D:\iiq\WEB-INF\lib && build.bat
set PLUGIN_NAME=TurnOnLoggers
if not defined IIQ_LIB set "IIQ_LIB=C:\identityiq_installation\identityiq_home\WEB-INF\lib"
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-11.0.30.7-hotspot"

set "OUT_DIR=%~dp0build"
set "CLASSES_DIR=%OUT_DIR%\classes"
set "LIB_DIR=%~dp0lib"
set JAR_NAME=turn-on-loggers.jar
set ZIP_NAME=%PLUGIN_NAME%.zip
set "JAVAC=%JAVA_HOME%\bin\javac.exe"
set "JAR_EXE=%JAVA_HOME%\bin\jar.exe"

REM ---- Preflight --------------------------------------------------------
if not exist "%IIQ_LIB%\identityiq.jar" (
    echo ERROR: identityiq.jar not found at %IIQ_LIB%
    echo Set IIQ_LIB to your IdentityIQ WEB-INF\lib folder.
    exit /b 1
)
if not exist "%JAVAC%" (
    echo ERROR: javac.exe not found at %JAVAC%
    echo Set JAVA_HOME to a JDK 11 install.
    exit /b 1
)

REM ---- Clean ------------------------------------------------------------
if exist "%OUT_DIR%" rmdir /S /Q "%OUT_DIR%"
if exist "%LIB_DIR%" rmdir /S /Q "%LIB_DIR%"
if exist "%~dp0%ZIP_NAME%" del /Q "%~dp0%ZIP_NAME%"
mkdir "%CLASSES_DIR%"
mkdir "%LIB_DIR%"

REM ---- Collect sources --------------------------------------------------
REM javac does not recurse into directories, so build an argument file.
if exist "%OUT_DIR%\sources.txt" del /Q "%OUT_DIR%\sources.txt"
for /R "%~dp0src" %%F in (*.java) do echo %%F>>"%OUT_DIR%\sources.txt"

REM ---- Compile ----------------------------------------------------------
echo Compiling...
"%JAVAC%" -source 11 -target 11 -encoding UTF-8 -nowarn ^
    -cp "%IIQ_LIB%\*" ^
    -d "%CLASSES_DIR%" ^
    "@%OUT_DIR%\sources.txt"
if errorlevel 1 (
    echo ERROR: compile failed.
    exit /b 1
)

REM ---- Jar --------------------------------------------------------------
echo Packaging %JAR_NAME%...
pushd "%CLASSES_DIR%"
"%JAR_EXE%" cf "%LIB_DIR%\%JAR_NAME%" com
popd
if errorlevel 1 (
    echo ERROR: jar packaging failed.
    exit /b 1
)

REM ---- Zip --------------------------------------------------------------
REM jar.exe, NOT PowerShell Compress-Archive: Compress-Archive writes entry
REM paths with backslashes, and IIQ's PluginsCache looks them up with forward
REM slashes, which surfaces as "Premature end of file" when the page loads.
echo Packaging %ZIP_NAME%...
pushd "%~dp0"
"%JAR_EXE%" cfM "%ZIP_NAME%" manifest.xml ui lib import
popd
if errorlevel 1 (
    echo ERROR: zip packaging failed.
    exit /b 1
)

echo.
echo ========================================================
echo Done:  %~dp0%ZIP_NAME%
echo Install: gear icon -^> Plugins -^> New -^> upload the zip
echo ========================================================
endlocal
