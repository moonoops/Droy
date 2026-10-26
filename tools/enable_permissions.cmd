@echo off
setlocal
for /f "tokens=2 delims=:" %%G in ('chcp') do set "droy_original_cp=%%G"
chcp 65001 >nul
set "droy_result=2"
if defined DROY_PYTHON goto configured_python
where py >nul 2>nul
if errorlevel 1 goto python_fallback
py -3 -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >nul 2>nul
if errorlevel 1 goto wrong_python
py -3 "%~dp0enable_permissions.py" %*
set "droy_result=%errorlevel%"
goto finished
:python_fallback
where python >nul 2>nul
if errorlevel 1 goto missing_python
python -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >nul 2>nul
if errorlevel 1 goto wrong_python
python "%~dp0enable_permissions.py" %*
set "droy_result=%errorlevel%"
goto finished
:configured_python
"%DROY_PYTHON%" -c "import sys; sys.exit(0 if sys.version_info >= (3, 8) else 1)" >nul 2>nul
if errorlevel 1 goto wrong_python
"%DROY_PYTHON%" "%~dp0enable_permissions.py" %*
set "droy_result=%errorlevel%"
goto finished
:wrong_python
echo 当前 Python 无法运行本工具。需要 Python 3.8 或更新版本，请检查 DROY_PYTHON。
echo 官方下载：https://www.python.org/downloads/
goto finished
:missing_python
echo 未找到 Python。请安装 Python 3.8 或更新版本，或设置 DROY_PYTHON。
echo 官方下载：https://www.python.org/downloads/
:finished
if "%~1"=="" (
    echo.
    echo 按任意键关闭…
    pause >nul
)
if defined droy_original_cp chcp %droy_original_cp% >nul
exit /b %droy_result%
