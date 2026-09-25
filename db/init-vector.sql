-- 容器首启时执行（docker-entrypoint-initdb.d）：在业务库里启用 pgvector 扩展
--
-- 注意：PostgreSQL 的注释是 `--`，不是 `#`（`#` 是 MySQL 语法）。
-- 曾经写成 `#` 导致初始化脚本 syntax error、容器启动即退出，且因为是首次初始化失败
-- 必须连数据卷一起删掉才能重来。
--
-- 业务表与向量表（vector_store）放在同一个 PostgreSQL 库里，单库更简单：
-- 少一个组件、少一份备份，也让混合检索的关键词通道（tsvector）能直接查 vector_store。
CREATE EXTENSION IF NOT EXISTS vector;
