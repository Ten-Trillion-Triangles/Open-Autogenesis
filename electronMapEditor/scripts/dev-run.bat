@echo off
REM dev-run.bat — build, launch, and test the Autogenesis Map Editor on Windows.
REM Usage: scripts\dev-run.bat [--rebuild] [--no-sandbox] [--e2e] [--help]

setlocal EnableDelayedExpansion

set "SCRIPT_DIR=%~dp0"
set "MODULE_DIR=%SCRIPT_DIR%.."
for %%I in ("%MODULE_DIR%") do set "MODULE_DIR=%%~fI"
set "REPO_DIR=%MODULE_DIR%\.."
for %%I in ("%REPO_DIR%") do set "REPO_DIR=%%~fI"
set "APPIMAGE=%MODULE_DIR%\build\electron\Autogenesis-MapEditor-windows-0.1.0.exe"

set "DO_REBUILD=false"
set "DO_E2E=false"
set "SANDBOX_FLAG="
set "GRADLE_FLAGS=--no-daemon"

if "%~1"=="" goto :run

:parse_args
if "%~1"=="" goto :run
if /i "%~1"=="--rebuild" (
    set "DO_REBUILD=true"
    shift
    goto :parse_args
)
if /i "%~1"=="--no-sandbox" (
    set "SANDBOX_FLAG=--no-sandbox"
    shift
    goto :parse_args
)
if /i "%~1"=="--e2e" (
    set "DO_E2E=true"
    shift
    goto :parse_args
)
if /i "%~1"=="--help" goto :help
if /i "%~1"=="-h" goto :help
echo Unknown option: %~1
echo Run scripts\dev-run.bat --help for usage.
exit /b 2

:help
echo dev-run.bat — build, launch, and test the Autogenesis Map Editor on Windows.
echo.
echo Usage: scripts\dev-run.bat [options]
echo.
echo Options:
echo     --rebuild       Force a clean Gradle build before launching.
echo     --no-sandbox    Launch the EXE with Chromium's renderer sandbox disabled.
echo                     Use this when Windows Defender or Controlled Folder Access
echo                     blocks the renderer sandbox token elevation.
echo     --e2e           Run the Playwright end-to-end suite instead of launching.
echo     --help          Show this message.
echo.
echo Environment variables:
echo     GRADLE_FLAGS    Extra arguments appended to every gradle invocation.
exit /b 0

:run
if "%DO_REBUILD%"=="true" (
    echo [dev-run] Clean rebuild requested
    cd /d "%REPO_DIR%" && call gradlew %GRADLE_FLAGS% :electronMapEditor:clean :electronMapEditor:packageWindows
    if errorlevel 1 exit /b %errorlevel%
) else (
    echo [dev-run] Incremental build
    cd /d "%REPO_DIR%" && call gradlew %GRADLE_FLAGS% :electronMapEditor:packageWindows
    if errorlevel 1 exit /b %errorlevel%
)

if "%DO_E2E%"=="true" goto :e2e
goto :launch

:launch
if not exist "%APPIMAGE%" (
    echo [dev-run] EXE not found at %APPIMAGE% — run with --rebuild
    exit /b 1
)
if defined SANDBOX_FLAG (
    echo [dev-run] Launching with sandbox disabled (%SANDBOX_FLAG%)
) else (
    set "SANDBOX_BIN=%MODULE_DIR%\build\electron\win-unpacked\Autogenesis Map Editor.exe"
    if exist "!SANDBOX_BIN!" (
        REM No SUID concept on Windows — flag Defender issues via the README's troubleshooting section.
        REM Nothing to pre-flight automatically.
    )
)
echo [dev-run] Launching %APPIMAGE%
"%APPIMAGE%" %SANDBOX_FLAG%
exit /b %errorlevel%

:e2e
if not exist "%APPIMAGE%" (
    echo [dev-run] EXE not found at %APPIMAGE% — run with --rebuild
    exit /b 1
)
set "NODE20=%REPO_DIR%\electronMapEditor\.gradle\nodejs\node-v20.9.0-win-x64"
if not exist "%NODE20%\node.exe" (
    echo [dev-run] Node 20.9.0 not found at %NODE20%
    echo [dev-run] Run a packageWindows task once to provision it.
    exit /b 1
)
set "PATH=%NODE20%;%PATH%"
set "E2E_DIR=%MODULE_DIR%\e2e"
echo [dev-run] Compiling e2e suite
cd /d "%E2E_DIR%" && call npx tsc
if errorlevel 1 exit /b %errorlevel%
echo [dev-run] Running Playwright e2e against the packaged EXE
cd /d "%E2E_DIR%" && call npm test
exit /b %errorlevel%