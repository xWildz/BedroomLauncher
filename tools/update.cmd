@echo off
rem Builds the TV and phone apps (release), installs the TV app, puts the phone APK in releases\. Log: update-log.txt
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" %*
