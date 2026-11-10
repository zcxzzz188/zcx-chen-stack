@echo off
rem Test-only docker shim. Enabled only by script/deploy/tests in a child PATH.
pwsh -NoProfile -NonInteractive -File "%~dp0fake-docker.ps1" %*
exit /b %ERRORLEVEL%
