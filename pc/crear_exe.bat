@echo off
rem Crea RTSPViewerControl.exe (un solo archivo, sin consola) con PyInstaller.
rem Requiere Python 3 instalado. El .exe queda en la carpeta dist\.
cd /d "%~dp0"
python -m pip install --upgrade pyinstaller || goto :error
python -m PyInstaller --onefile --noconsole --name RTSPViewerControl rtspviewer_control.pyw || goto :error
echo.
echo Listo: dist\RTSPViewerControl.exe
pause
exit /b 0

:error
echo.
echo No se pudo crear el .exe.
pause
exit /b 1
