@echo off
:: Kite CLI Installer for Windows (CMD wrapper)
:: Usage: curl -fsSL https://cli.kitelang.cloud/scripts/install.bat -o install.bat && install.bat
::
:: This script calls the PowerShell installer.
:: For PowerShell users, run directly: irm https://cli.kitelang.cloud/scripts/install.ps1 | iex

echo.
echo   _  ___ _
echo  ^| ^|/ (_) ^|_ ___
echo  ^| ' ^<^| ^|  _/ -_)
echo  ^|_^|\_\_^|\__\___^|
echo.
echo Kite CLI Installer
echo ==================
echo.

:: Pass through KITE_VERSION if set
if defined KITE_VERSION (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "$env:KITE_VERSION='%KITE_VERSION%'; irm https://cli.kitelang.cloud/scripts/install.ps1 | iex"
) else (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "irm https://cli.kitelang.cloud/scripts/install.ps1 | iex"
)
