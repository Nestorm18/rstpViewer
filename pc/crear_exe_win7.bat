@echo off
rem Crea PanelCentral-win7-32bit.exe para Windows 7 de 32 bits (también funciona en Windows 8/10/11).
rem Usa Python 3.8 de 32 bits (la última versión que funciona en Windows 7) y PyInstaller 5.13.2.
rem Requiere uv (https://docs.astral.sh/uv/). El .exe queda en la carpeta dist\.
cd /d "%~dp0"
uv python install cpython-3.8-windows-x86 || goto :error
if not exist .venv32\Scripts\python.exe (
    uv venv --python cpython-3.8-windows-x86 .venv32 || goto :error
)
uv pip install --python .venv32\Scripts\python.exe pyinstaller==5.13.2 || goto :error
.venv32\Scripts\python.exe -m PyInstaller --onefile --console --icon panel_central.ico --name PanelCentral-win7-32bit panel_central.py || goto :error
echo.
echo Listo: dist\PanelCentral-win7-32bit.exe
pause
exit /b 0

:error
echo.
echo No se pudo crear el .exe.
pause
exit /b 1
