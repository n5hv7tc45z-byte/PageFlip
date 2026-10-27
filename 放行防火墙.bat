@echo off
chcp 936 >nul
title 翻页器 - 放行防火墙

net session >nul 2>&1
if errorlevel 1 (
  echo 需要管理员权限，正在提权...
  powershell -NoProfile -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
  exit /b
)

netsh advfirewall firewall delete rule name="PageFlip" >nul 2>&1
netsh advfirewall firewall delete rule name="PageFlip-UDP" >nul 2>&1
netsh advfirewall firewall add rule name="PageFlip" dir=in action=allow protocol=TCP localport=8000-8020 profile=private,domain
netsh advfirewall firewall add rule name="PageFlip-UDP" dir=in action=allow protocol=UDP localport=8000-8020 profile=private,domain

if errorlevel 1 (
  echo.
  echo   [失败] 添加防火墙规则失败，请手动在 Windows 防火墙中允许 Python。
) else (
  echo.
  echo   [成功] 已放行 TCP 和 UDP 的 8000-8020 端口，手机现在可以访问。
)
echo.
pause
