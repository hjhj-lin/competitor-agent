#!/bin/bash
# 竞品分析Agent平台 - 一键启动脚本

set -e

echo "========================================="
echo "  竞品分析Agent平台 - Docker部署"
echo "========================================="

# 检查.env文件
if [ ! -f .env ]; then
    echo "创建.env文件..."
    cat > .env << 'ENVEOF'
DEEPSEEK_API_KEY=your-deepseek-api-key
MYSQL_PASSWORD=123456
JWT_SECRET=prod-jwt-key-change-me
KIMI_API_KEY=
TAVILY_API_KEY=
ENVEOF
    echo "请编辑 .env 文件填入API Key后重新运行"
    exit 1
fi

# 构建并启动
echo "构建Docker镜像..."
docker compose build

echo "启动服务..."
docker compose up -d

echo ""
echo "等待MySQL就绪..."
sleep 10

echo "========================================="
echo "  部署完成！"
echo "  应用地址: http://localhost:8080"
echo "  API文档:  http://localhost:8080/swagger-ui.html"
echo "========================================="
