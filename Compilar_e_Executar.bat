@echo off
setlocal
cd /d "%~dp0"
where javac >nul 2>nul
if errorlevel 1 (
  echo O compilador javac nao foi encontrado.
  echo Instale um JDK 17 ou mais recente e marque a opcao para adicionar Java ao PATH.
  pause
  exit /b 1
)
if not exist out mkdir out
javac --release 17 -encoding UTF-8 -d out src\BenchmarkOrdenacaoApp.java
if errorlevel 1 (
  echo Falha ao compilar. Confira as mensagens acima.
  pause
  exit /b 1
)
java -cp out BenchmarkOrdenacaoApp
endlocal
