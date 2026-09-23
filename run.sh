#!/usr/bin/env bash
# 启动开发服务。用法：
#   DEEPSEEK_API_KEY=sk-xxx ./run.sh              # 带 Key 启动
#   ./run.sh --server.port=8081                   # 透传任意 Spring Boot 参数
#   SPRING_PROFILES_ACTIVE=postgres ./run.sh      # 切 PostgreSQL
set -euo pipefail

cd "$(dirname "$0")"

# Maven 探测顺序：Wrapper（免装 Maven）→ 系统 mvn → 本机隔离版兜底
if [ -x "./mvnw" ]; then
  exec ./mvnw -B spring-boot:run "$@"
elif command -v mvn >/dev/null 2>&1; then
  exec mvn -B spring-boot:run "$@"
elif [ -x "../.tools/apache-maven-3.9.16/bin/mvn" ]; then
  exec ../.tools/apache-maven-3.9.16/bin/mvn -s ../.tools/settings.xml -B spring-boot:run "$@"
else
  echo "未找到 Maven。请安装 Maven 3.9+，或确认项目根目录存在 mvnw。" >&2
  exit 1
fi
