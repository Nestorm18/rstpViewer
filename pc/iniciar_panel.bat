@echo off
rem Abre el panel central de RTSP Viewer (requiere Python 3).
cd /d "%~dp0"
python panel_central.py
if errorlevel 1 pause
