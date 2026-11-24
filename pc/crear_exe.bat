@echo off
rem Crea PanelCentral.exe (un solo archivo) con PyInstaller.
rem Requiere Python 3 instalado. El .exe queda en la carpeta dist\.
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller || goto :error
python -m PyInstaller --onefile --console --icon panel_central.ico --name PanelCentral panel_central.py || goto :error
echo.
echo Listo: dist\PanelCentral.exe
pause
exit /b 0

:error
echo.
echo No se pudo crear el .exe.
pause
exit /b 1
