@echo off
setlocal
cd /d "%~dp0"
where java >nul 2>nul
if errorlevel 1 (
  echo Java nao foi encontrado neste computador.
  echo Instale o Java 17 ou mais recente e marque a opcao para adicionar Java ao PATH.
  echo Depois, execute este arquivo novamente.
  pause
  exit /b 1
)
java -jar "OrdenacaoParalela.jar"
if errorlevel 1 (
  echo.
  echo A aplicacao foi encerrada com erro. Verifique se o Java 17+ esta instalado.
  pause
)
endlocal
