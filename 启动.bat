@echo off
chcp 936 >nul
cd /d "%~dp0"
title 翻页器 PageFlip

set "PY="
if exist "C:\Program Files\Python314\python.exe" set "PY=C:\Program Files\Python314\python.exe"
if not defined PY if exist "C:\Program Files\Python313\python.exe" set "PY=C:\Program Files\Python313\python.exe"
if not defined PY if exist "C:\Program Files\Python312\python.exe" set "PY=C:\Program Files\Python312\python.exe"
if not defined PY if exist "C:\Program Files\Python311\python.exe" set "PY=C:\Program Files\Python311\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python313\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python313\python.exe"
if not defined PY if exist "%LOCALAPPDATA%\Programs\Python\Python312\python.exe" set "PY=%LOCALAPPDATA%\Programs\Python\Python312\python.exe"
if not defined PY python -V >nul 2>nul && set "PY=python"

if not defined PY (
  echo.
  echo   [错误] 没有找到 Python
  echo   请安装 Python 3.8 或更高版本，安装时务必勾选 "Add python.exe to PATH"
  echo   下载地址 https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)

echo   使用解释器: %PY%
echo.
"%PY%" server.py %*
echo.
pause
