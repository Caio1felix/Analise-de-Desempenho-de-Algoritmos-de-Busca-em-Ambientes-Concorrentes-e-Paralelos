@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java nao foi encontrado. Instale Java 17+.
  pause
  exit /b 1
)
java -jar OrdenacaoParalela.jar --self-test
pause
endlocal
