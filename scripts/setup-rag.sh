#!/usr/bin/env bash
# RAG 环境准备：PostgreSQL(17) + pgvector + Ollama(bge-m3)
# 要写 /opt/homebrew，请在**你自己的终端**里执行（受限沙箱跑不了）。
#
# 踩坑记录（都是实测踩出来的）：
# 1) 阿里云 brew 镜像缺 gettext / python@3.14 的 bottle（404）→ 本脚本默认用清华镜像
# 2) brew 的 pgvector 0.8.6 只为 postgresql@17 / @18 编译扩展文件，
#    PG16 的 share 目录里没有 vector.control，会报 extension "vector" is not available
#    → 所以这里用 postgresql@17（扩展文件现成）
# 3) brew 装 ollama 会连带拉 python@3.14 + mlx（大且易卡）→ 这里直接走官方 zip
set -e

export HOMEBREW_BOTTLE_DOMAIN="${HOMEBREW_BOTTLE_DOMAIN:-https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles}"

PG_VERSION=17
PG_BIN="/opt/homebrew/opt/postgresql@${PG_VERSION}/bin"

echo "== 0/5 环境检查 =="
echo "  HOMEBREW_BOTTLE_DOMAIN=$HOMEBREW_BOTTLE_DOMAIN"
if echo "$HOMEBREW_BOTTLE_DOMAIN" | grep -q aliyun; then
  echo "⚠️  阿里云镜像缺 gettext 等 bottle，请改用："
  echo "    export HOMEBREW_BOTTLE_DOMAIN=https://mirrors.tuna.tsinghua.edu.cn/homebrew-bottles"
  exit 1
fi

echo "== 1/5 安装 PostgreSQL ${PG_VERSION} + pgvector =="
if [ -x "$PG_BIN/bin/psql" ]; then
  echo "postgresql@${PG_VERSION} 已安装，跳过"
else
  # PG16 与 PG17 都默认 5432，先停掉避免端口冲突
  brew services stop postgresql@16 2>/dev/null || true
  brew install "postgresql@${PG_VERSION}"
fi
brew list --versions pgvector >/dev/null 2>&1 || brew install pgvector

echo "== 2/5 启动 PostgreSQL ${PG_VERSION} =="
brew services stop postgresql@16 2>/dev/null || true
brew services start "postgresql@${PG_VERSION}"
sleep 5
"$PG_BIN/pg_isready" -h 127.0.0.1 -p 5432 || true

echo "== 3/5 建库并启用 vector 扩展 =="
"$PG_BIN/createdb" -h 127.0.0.1 interview_vector 2>/dev/null || echo "库已存在，跳过"
"$PG_BIN/psql" -h 127.0.0.1 -d interview_vector -c "CREATE EXTENSION IF NOT EXISTS vector;"
"$PG_BIN/psql" -h 127.0.0.1 -d interview_vector -c "SELECT extname, extversion FROM pg_extension WHERE extname='vector';"

echo "== 4/5 安装 Ollama（官方包，绕开 python@3.14 / mlx）=="
OLLAMA_BIN=/Applications/Ollama.app/Contents/Resources/ollama
if [ -x "$OLLAMA_BIN" ]; then
  echo "Ollama.app 已存在，跳过"
else
  curl -L --progress-bar -o /tmp/Ollama-darwin.zip https://ollama.com/download/Ollama-darwin.zip
  unzip -q -o /tmp/Ollama-darwin.zip -d /Applications
  echo "已解压到 /Applications/Ollama.app"
fi

echo "== 5/5 启动 Ollama 并拉取 bge-m3（约 1.2GB，最慢的一步）=="
pgrep -f "Ollama.app/Contents/Resources/ollama" >/dev/null \
  || (nohup "$OLLAMA_BIN" serve >/tmp/ollama.log 2>&1 &)
sleep 4
"$OLLAMA_BIN" pull bge-m3
"$OLLAMA_BIN" list | head -5

echo
echo "✅ 完成。自检："
echo "  $PG_BIN/psql -h 127.0.0.1 -d interview_vector -c '\\dx vector'"
echo "  curl -s http://localhost:11434/api/embeddings -d '{\"model\":\"bge-m3\",\"prompt\":\"你好\"}' | head -c 80"
echo
echo "然后启动应用："
echo "  cd $(cd "$(dirname "$0")/.." && pwd) && ./run.sh"
