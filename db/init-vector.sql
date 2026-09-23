# 容器启动时执行：在业务库里启用 pgvector 扩展
-- 业务表与向量表（vector_store）都放在同一个 PostgreSQL 库里，单库更简单
CREATE EXTENSION IF NOT EXISTS vector;
