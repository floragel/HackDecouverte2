@echo off
rem Starts the MB graph editor on http://127.0.0.1:8765/editor.html (close this window to stop it)
cd /d "%~dp0"
python editor_server.py
pause
