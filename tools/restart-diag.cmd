@echo off
rem Read-only: saves why the TV last restarted to restart-diag.txt
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0restart-diag.ps1"
