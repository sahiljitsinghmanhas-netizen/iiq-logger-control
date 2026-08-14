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

REM ---- Render check -----------------------------------------------------
REM Executes the page script against a stub DOM. A parse-only check is not
REM enough: 2.2.0-2.4.0 shipped a script that failed to parse at all, because
REM the check grepped for text jjs never printed. This runs the real thing and
REM fails the build if the page does not render.
set "JJS=%JAVA_HOME%\bin\jjs.exe"
if exist "%JJS%" (
    echo Running render check...
    "%JJS%" "%~dp0tools\render-check.js" -- "%~dp0ui\js\turnOnLoggers.js" "%~dp0tools\state-fixture.json" "%~dp0tools\state-fixture-logs.json"
    if errorlevel 1 (
        echo ERROR: render check failed - the page would not load. Build aborted.
        exit /b 1
    )
) else (
    echo WARNING: jjs not found, skipping render check.
)

REM ---- Help-page images -------------------------------------------------
REM The help page ships inside the zip and reads its images from ui\img, but
REM the screenshot tool writes to docs\screenshots. Those were kept in step by
REM hand, which means they were not: the help page showed a UI two releases old
REM while the README showed the current one. Copy on every build instead.
echo Syncing help-page images...
if not exist "%~dp0ui\img" mkdir "%~dp0ui\img"
copy /Y "%~dp0docs\screenshots\*.png" "%~dp0ui\img\" >nul
if errorlevel 1 (
    echo ERROR: could not copy docs\screenshots to ui\img.
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
