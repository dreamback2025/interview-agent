#!/usr/bin/env bash
# 本地以「PostgreSQL 存业务数据 + pgvector 存向量」的方式启动（不依赖 Docker）
# 这套环境变量与 docker-compose.yml 里 app 服务完全一致，方便本地模拟容器行为。
#
#   bash scripts/run-postgres-local.sh
#
# 前置：
#   1. PostgreSQL 17 已启动（brew services start postgresql@17）
#   2. 已建库：createdb interview_vector
#   3. Ollama 已启动并拉过 bge-m3
set -e
cd "$(dirname "$0")/.."

export SPRING_PROFILES_ACTIVE=postgres
export BIZ_DB_URL="${BIZ_DB_URL:-jdbc:postgresql://127.0.0.1:5432/interview_vector}"
export BIZ_DB_USER="${BIZ_DB_USER:-$(whoami)}"
export BIZ_DB_PASSWORD="${BIZ_DB_PASSWORD:-}"
export VECTOR_DB_URL="${VECTOR_DB_URL:-jdbc:postgresql://127.0.0.1:5432/interview_vector}"
export VECTOR_DB_USER="${VECTOR_DB_USER:-$(whoami)}"
export VECTOR_DB_PASSWORD="${VECTOR_DB_PASSWORD:-}"
export OLLAMA_BASE_URL="${OLLAMA_BASE_URL:-http://localhost:11434}"

echo "SPRING_PROFILES_ACTIVE=$SPRING_PROFILES_ACTIVE"
echo "BIZ_DB_URL=$BIZ_DB_URL  (user=$BIZ_DB_USER)"
echo "VECTOR_DB_URL=$VECTOR_DB_URL"
echo "OLLAMA_BASE_URL=$OLLAMA_BASE_URL"
echo
exec java -jar target/interview-agent-0.1.0-SNAPSHOT.jar --server.port="${PORT:-8080}"
