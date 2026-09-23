@echo off
rem ============================================================
rem  SRTP Server 本地一键启动（Java 17）
rem
rem  敏感配置（数据库密码 / Python 路径 / API Key）从
rem  local-secrets.bat 读取 —— 该文件已被 .gitignore 忽略，不会入库。
rem  首次使用：复制 local-secrets.bat.example 为 local-secrets.bat 并填值。
rem ============================================================
setlocal

set "JAVA_HOME=C:\Program Files\Java\jdk-17"
set "PATH=%JAVA_HOME%\bin;%PATH%"

cd /d "%~dp0"

if exist "local-secrets.bat" (
    call "local-secrets.bat"
) else (
    echo [WARN] 未找到 local-secrets.bat
    echo        请复制 local-secrets.bat.example 为 local-secrets.bat 并填入真实配置。
    echo.
)

if not exist "target\srtp-server-1.0.0.jar" (
    echo [ERROR] 未找到 target\srtp-server-1.0.0.jar
    echo         请先构建前端与后端：
    echo           cd /d E:\srtp\srtp-web ^&^& npm run build
    echo           cd /d E:\srtp\srtp-server ^&^& mvn package -DskipTests
    exit /b 1
)

echo ============================================================
echo  启动 SRTP Server
echo  数据库: %DB_USERNAME%@%DB_URL%
echo  Python: %PYTHON_EXECUTABLE%
echo  访问:   http://localhost:8090
echo ============================================================
java -jar target\srtp-server-1.0.0.jar

endlocal
