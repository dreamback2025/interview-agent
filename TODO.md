# TODO

## 🟡 待办（不阻塞，可随时做）

- [ ] **统一数据到 PostgreSQL**：目前 `data/`（H2）里还留着 1 条早期冒烟测试数据「某互联网公司」，
      真实数据在 PG `interview_vector`。要么以后都用 `scripts/run-postgres-local.sh`，
      要么把这条测试数据删掉（H2 文件库单进程独占，删之前要先停掉占用它的实例）
- [ ] 页面上补「删除文档」按钮（接口 `DELETE /api/knowledge/docs/{docId}` 已实现，页面暂未接）

## ✅ 已修的易错点

- [x] **多实例看错库**：页面顶部现在显示「库：PostgreSQL · 记录 1 · 报告 2 · 笔记 1 · embedding ollama」，
      接口 `GET /api/debug/info`（连接串脱敏），不再靠猜
- [x] 清理自检库 `data-verify`（17 条测试数据）与 `data-smoke`
- [ ] 页面知识库区块显示当前 embedding 模式（ollama / stub），避免再被静默降级坑到

## ✅ Docker 编排（已完成，构建未实测）

- [x] `Dockerfile`：多阶段（maven 构建 → JRE 运行）、非 root、`dependency:go-offline` 缓存依赖、
      构建阶段用阿里云 Maven 镜像、健康检查用 `/dev/tcp`（temurin 镜像无 curl）
- [x] `docker-compose.yml`：postgres(pgvector) + ollama + app；mysql/redis 收进 legacy profile
- [x] `db/init-vector.sql`：容器首启启用 vector 扩展
- [x] `application-postgres.yml`：业务数据从 H2 切到 PostgreSQL（**实测 PG 上自动建 6 张表，
      `position` 关键字正确转义，录入/分析/RAG/记忆全通**）
- [x] `scripts/run-postgres-local.sh`：与 compose 完全相同的 env 契约，本地即可模拟容器
- [x] YAML 语法 + env 契约校验通过
- [ ] ⚠️ **未实测**：本机没装 Docker，`docker compose up --build` 需要你在装好 Docker 后跑一次

## ✅ 全功能自检（2026-09-23，28/28 通过）

`bash scripts/verify-all.sh` 一键回归，覆盖：

- 服务健康 + LLM 模式（deepseek）
- 录入 / 列表 / 详情 / 普通分析 / Agent 分析 / 报告落库 / 历史回看 / 导出 JSON
- BGE-M3 入库切分 6 段 + 4 个查询全部命中正确段落（73.7% / 69.8% / 74.4% / 78.7%）
- 三个工具（searchWeakPoints / getJdRequirements / searchKnowledgeBase）
- 模拟面试建会话 / 打分 / 追问 / 总评 / 回放
- SSE 流式复盘（2370 字 + `text/event-stream` + Markdown 结构）/ 会话记忆注入

## ✅ 流式输出 + 会话记忆（已完成并验证）

- [x] `GET /api/interviews/{id}/analysis-stream` → SSE 逐段推送 Markdown 复盘报告
- [x] 页面「流式复盘」按钮：fetch + ReadableStream 逐字渲染（jsdom 实测输出 2447 字）
- [x] 流式走 Markdown 而非 JSON（流式下 JSON 中途残缺，无法结构化校验），且不落库、页面有提示
- [x] `MemoryService` 会话记忆：把其他历次面试的结论 + 当时盲区注入当前分析
- [x] 实测模型主动点名：「**与历史复盘中的盲区完全重合，属于同一问题反复出现且未补强**」
- [x] 自检端点 `GET /api/debug/memory?excludeRecordId=&limit=` 可直接看注入原文
- [x] 未用 Redis 的原因写进 README：记忆本就以报告形式存在 `interview_analysis` 表，
      单机 MVP 用不上 Redis；换实现只需替换 `MemoryService`

## ✅ RAG 端到端（已完成，含真实语义 embedding）

- [x] 环境：PostgreSQL 17（**必须 17/18，16 没有 pgvector 扩展文件**）+ pgvector 0.8.6
      + Ollama 0.34.2 + bge-m3（1024 维，实测 embedding 接口返回 1024 维）
- [x] `vector_store` 表自动创建：`embedding vector(1024)` + HNSW 索引（`vector_cosine_ops`）
- [x] 导入示例笔记 → **按标题切出 6 段，一 chunk 一主题**
- [x] 检索 6/6 命中正确段落；相似度从哈希兜底的 13~45% 提升到 **bge-m3 的 61~79%**
- [x] 删除接口：`DELETE /api/knowledge/docs/{docId}` → 204，PG 中 chunk 归零
- [x] RAG 增强分析：模型盲区里出现**只有笔记里才有的细节**
      （`-XX:MaxGCPauseMillis 默认 200ms`、`布隆过滤器无法删除元素`）
- [x] 页面知识库区块：入库 + 检索按相似度排序展示
- [x] Bug 修复：**yml 缩进把 `ollama:` 写成了 `openai:` 的子节点** →
      `spring.ai.ollama.*` 失效、embedding 静默退回 `mxbai-embed-large` 并报 404

## ✅ 已完成并验证

- [x] 最小闭环：录入 → 分析 → 补强建议；报告落库 + 历史回看 + 导出 JSON
- [x] 网页表单（static/index.html），jsdom 冒烟零运行时错误（`SKIP_SLOW=1` 只跑快检查）
- [x] Bug 修复：`/api/llm/mode` 返回 text/plain 导致页面永远显示 stub
- [x] Bug 修复：静态资源缺失被兜底处理器吞成 500（应为 404）
- [x] Bug 修复：向量数据源被 JPA 抢走导致 Postgres 没起时整个应用起不来
- [x] Bug 修复：**切分粒度太粗**（1400 字只切 3 段导致主题混杂、检索串味）→ 改为标题优先切分
- [x] 工具调用：3 个 `@Tool` + Agent 分析（DEBUG 日志确认模型真的自主调用了工具）
- [x] 模拟面试：出题 → 打分 → 追问 → 总分（浅答 20 分→追问→85 分结束本题，总分 55）

## ✅ Git 仓库就绪（2026-09-23）

- [x] `git init` + 首次提交：87 文件 / 6132 行，`.git` 仅 **756K**（确认无大文件污染历史）
- [x] 密钥改走环境变量：`application.yml` 只留占位符 `${DEEPSEEK_API_KEY:sk-placeholder-not-set}`，
      未配置时自动降级 stub（与 `LlmConfig` 的判定逻辑一致）
- [x] 移出 **117MB 的 `Ollama.dmg`**（安装包不进版本库），`.gitignore` 补 `*.dmg` / `*.pkg`
- [x] `run.sh` 去掉本机 Maven 绝对路径 → 新增 **Maven Wrapper**（`./mvnw`），clone 后开箱可跑
- [x] 清理脚本里的绝对路径（`verify-all.sh` 的 Python 自动探测、`setup-rag.sh` 的提示路径）
- [x] 新增单元测试 `TextSplitterTest`（5 项：标题切分 / 代码块保护 / 超长拆分 / 长度上限）
- [x] 新增 `LICENSE`(MIT)、`.env.example`、`.github/workflows/build.yml`（CI 无需任何密钥）
- [x] `/api/debug/*` 加开关 `DEBUG_ENDPOINTS`，对外部署可关闭
- [x] README 顶部过期描述修正（原写「暂不含 RAG / Function Calling / SSE」），补启动/贡献/License 章节

## ⏭️ 下一步计划

- **截图沉淀**：浏览器截录入页与报告页，贴进 README
- **推送到 GitHub 后**：把 README 的 CI badge 与 `pom.xml` 的 `<url>` 换成真实仓库地址
