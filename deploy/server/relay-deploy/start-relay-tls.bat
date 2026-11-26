@echo off
setlocal
cd /d "%~dp0"

rem ============================================================
rem  S20 relay server - built-in TLS (wss / https)
rem  Comment out RELAY_SSL_CERT / RELAY_SSL_KEY if you put nginx
rem  in front (TLS terminated by nginx, relay stays plain ws).
rem ============================================================

set "RELAY_HOST=0.0.0.0"
set "RELAY_PORT=9000"
set "RELAY_TOKEN=s20-default-token"
set "RELAY_DB=%~dp0relay\s20relay.db"

rem 日志级别 DEBUG/INFO/WARNING/ERROR/CRITICAL（默认 INFO；设 WARNING 只输出警告与错误）
set "LOG_LEVEL=INFO"

rem MySQL 8 mode (requires: pip install PyMySQL):
rem set "RELAY_DB_BACKEND=mysql"
rem set "RELAY_DB_HOST=127.0.0.1"
rem set "RELAY_DB_PORT=3306"
rem set "RELAY_DB_USER=root"
rem set "RELAY_DB_PASS="
rem set "RELAY_DB_NAME=s20relay"

set "RELAY_SSL_CERT=%~dp0certs\relay.crt"
set "RELAY_SSL_KEY=%~dp0certs\relay.key"

echo [relay] starting https/wss on %RELAY_HOST%:%RELAY_PORT%
echo [relay] cert: %RELAY_SSL_CERT%
python relay_server.py

if errorlevel 1 (
  echo.
  echo [relay] exited with error - see messages above
  pause
)
