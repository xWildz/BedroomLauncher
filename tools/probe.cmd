@echo off
rem Read-only TV probe, writes probe-diag.txt
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0probe.ps1"
