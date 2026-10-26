@echo off
setlocal
cd /d "%~dp0"

echo.
echo   Hogo-QA-tool launcher (frontend + local static server)
echo   ------------------------------------
echo.

rem --- locate node ---
where node >nul 2>nul
if errorlevel 1 (
  echo   [ERROR] Node.js not found in PATH.
  echo   Install Node.js 22.22.2 or newer from https://nodejs.org/ and retry.
  echo.
  pause
  exit /b 1
)

rem --- frontend deps ---
if not exist "frontend\node_modules" (
  echo   Installing frontend dependencies...
  pushd frontend
  call npm ci
  popd
  if errorlevel 1 (
    echo   [ERROR] npm ci failed inside frontend\.
    pause
    exit /b 1
  )
)

rem --- build server version if missing or STALE ---
rem Staleness check + rebuild lives in frontend\scripts\ensure-build.mjs.
node frontend\scripts\ensure-build.mjs
if errorlevel 1 (
  echo   [ERROR] Build failed. Run "npx vite build" inside frontend\ to see details.
  echo.
  pause
  exit /b 1
)

echo   Starting local server (frontend + auth API), browser will open shortly...
rem backend/server.mjs 同时托管静态产物与 /api 登录接口（登录门禁依赖它）。
start "" http://127.0.0.1:8788/
node backend\server.mjs

echo.
echo   Server stopped.
pause
