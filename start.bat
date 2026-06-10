@echo off
chcp 65001 >nul
echo =========================================
echo   竞品分析Agent平台 - Docker部署
echo =========================================

if not exist .env (
    echo 创建.env文件...
    (
        echo DEEPSEEK_API_KEY=your-deepseek-api-key
        echo MYSQL_PASSWORD=123456
        echo JWT_SECRET=prod-jwt-key-change-me
        echo KIMI_API_KEY=
        echo TAVILY_API_KEY=
    ) > .env
    echo 请编辑 .env 文件填入API Key后重新运行
    exit /b 1
)

echo 构建Docker镜像...
docker compose build

echo 启动服务...
docker compose up -d

echo.
echo 等待MySQL就绪...
timeout /t 10 /nobreak >nul

echo =========================================
echo   部署完成！
echo   应用地址: http://localhost:8080
echo   API文档:  http://localhost:8080/swagger-ui.html
echo =========================================
