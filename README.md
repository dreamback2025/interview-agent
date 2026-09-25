# interview-agent · AI 面试复盘 Agent

[![build](https://github.com/dreamback2025/interview-agent/actions/workflows/build.yml/badge.svg)](https://github.com/dreamback2025/interview-agent/actions/workflows/build.yml)

> 一句话：**录入一次真实面试 → LLM 分析弱项 → RAG 检索知识库增强 → 输出可执行的补强计划 → 多轮模拟面试 → SSE 流式复盘。**

技术栈：Java 21 · Spring Boot 3.5.16 · Spring AI 1.1.8（DeepSeek，OpenAI 协议）· Ollama bge-m3 + PostgreSQL/pgvector · Redis · RabbitMQ · JPA · Docker Compose · SSE · Micrometer/Prometheus

**核心能力**：面试记录结构化落库 · 弱项分析 + 补强建议 · 知识库语义检索（标题切分 + 1024 维向量 + HNSW 索引）· Function Calling（`@Tool` 由模型自主调用）· 多轮模拟面试与打分 · SSE 流式复盘 · 跨记录会话记忆 · **结果缓存与幂等** · 28 项一键自检

**工程主线**：用后端手段解决 LLM 应用的规模化问题 —— 慢（缓存 + 幂等）、贵（限流）、不可靠（全链路降级）、不可观测（指标 + traceId）。LLM 与 RAG 只是客��链路的两端，工程难点在中间这段。

> 每一块功能都附**可复现的实测数据**（见下文各节）。


---

## 1. 快速开始

启动后浏览器打开 **<http://localhost:8080>** 即可使用（无需任何前端构建，页面由后端 `static/index.html` 直接提供）。

页面功能：填表单录入面试（题目行可增删）→ 左侧历史记录 → 右侧查看详情 → 点「分析弱项」看复盘报告（答得好/差、知识盲区、优先补 3 点）。
分析完成后弱项会自动回写到题目卡片上，用红色左边框标出。

> 页面改动后需重启服务才能生效（`Ctrl+C` 后重新 `./run.sh`）。

### 启动后访问地址

服务默认端口 **8080**，启动成功后访问：

| 用途 | 地址 |
|---|---|
| **网页界面**（录入面试 / 查看复盘报告） | <http://localhost:8080> |
| 健康检查 | <http://localhost:8080/actuator/health> |
| 查看当前模型模式（`deepseek` / `stub`） | <http://localhost:8080/api/llm/mode> |
| 模型联通性冒烟 | <http://localhost:8080/chat?q=你好> |
| H2 数据库控制台 | <http://localhost:8080/h2-console> |
| **运行指标**（缓存命中、任务、限流） | <http://localhost:8080/actuator/prometheus> |
| RabbitMQ 管理台（仅容器编排时） | <http://localhost:15672> |

容器编排占用的端口：`8080`(app) / `5432`(postgres) / `6379`(redis) / `5672`+`15672`(rabbitmq) / `11434`(ollama)。

打开首页若返回 `500 No static resource .`，说明当前进程是页面加入之前启动的旧进程，重启即可（H2 是文件库，已录入的数据不会丢）。

端口不是 8080 时：检查是否设置了 `SERVER_PORT` / `SERVER__PORT` 环境变量（Spring Boot 宽松绑定会覆盖 `server.port`），启动时显式加 `--server.port=8080` 即可，改端口则访问对应地址。

### 1.1 启动（只需要 JDK 21）

```bash
git clone <仓库地址> && cd interview-agent
./run.sh
```

- **Maven 不用装**：仓库自带 Maven Wrapper（`./mvnw`），`run.sh` 会优先使用它
- **数据库不用装**：默认 H2 文件库（`./data/`），零依赖即可跑通全链路
- **没有 Key 也能跑**：自动进入 `stub` 模式，除 LLM 外的链路（录入、落库、Prompt 拼装、JSON 解析、弱项回写）都能真实验证

依赖下载慢的话，把 `~/.m2/settings.xml` 换成国内镜像即可。

### 1.2 接入真实 DeepSeek

> **Key 只在进程启动时读取一次**，改配置后必须重启服务才生效。
> 启动日志里会直接打出解析结果，一眼就能确认：
> ```
> INFO  ... LlmConfig : LLM 模式 = deepseek（model=deepseek-chat）
> WARN  ... LlmConfig : LLM 模式 = stub（原因：未检测到可用 Key（为空或仍是占位符））
> ```
> 注意：环境变量被设成空字符串时，Spring **不会**回退到 `application.yml` 里的默认值，同样会掉进 stub。

```bash
export DEEPSEEK_API_KEY=sk-xxxxxxxx
./run.sh
```

> **Key 的三种放法**（都不会进版本库）：
> 1. 环境变量：`DEEPSEEK_API_KEY=sk-xxx ./run.sh`
> 2. 本地 profile：新建 `src/main/resources/application-local.yml`（已在 `.gitignore` 中），写入
>    `spring.ai.openai.api-key: sk-xxx`，然后 `SPRING_PROFILES_ACTIVE=local ./run.sh`
> 3. 从模板起步：`cp .env.example .env` 填好后 `set -a && source .env && set +a && ./run.sh`
>
> `application.yml` 里只保留占位符 `${DEEPSEEK_API_KEY:sk-placeholder-not-set}`，不会包含任何真实密钥。

确认是否切换成功：

```bash
curl http://127.0.0.1:8080/api/llm/mode   # 返回 deepseek 即已接真模型
curl "http://127.0.0.1:8080/chat?q=你好"   # 冒烟：能回话就说明模型通了
```

出现 `404` 或连接类报错时，把 base-url 换成带版本号的：

```bash
DEEPSEEK_BASE_URL=https://api.deepseek.com/v1 ./run.sh
```

### 1.3 一键跑通闭环

```bash
bash scripts/smoke.sh          # 服务已启动时
bash scripts/verify-all.sh     # 全功能自检：28 项，逐项 PASS/FAIL
./mvnw test                    # 单元测试（纯离线，不需要任何外部服务）
```

`scripts/` 下的脚本：

| 脚本 | 用途 |
|---|---|
| `smoke.sh` | 快速冒烟：录入 → 列表 → 详情 → 分析 → 出题 |
| `verify-all.sh` | **全功能自检（28 项）**：服务/模型、录入分析落库导出、RAG 检索命中断言、工具调用、模拟面试、流式 + 会话记忆 |
| `ui-smoke.js` | 用 jsdom 真实执行页面 JS（`SKIP_SLOW=1` 跳过慢步骤） |
| `ui-shot.js` | 用本机 Chrome 无头截图到 `docs/screenshots/`（需 `puppeteer-core`，仅开发用） |
| `setup-rag.sh` | RAG 环境安装（PostgreSQL 17 + pgvector + Ollama + bge-m3） |
| `run-postgres-local.sh` | 用与 `docker-compose.yml` 一致的 env 契约，本地以 PostgreSQL 启动 |
| `sample-note.json` | 示例八股笔记（验证切分与检索） |
| `eval/corpus.md` | **RAG 评测语料**：30 个知识点的八股笔记（`## <tag> \| <标题>` 格式，tag 用于自动判定命中） |
| `eval/queries.json` | **RAG 评测集**：40 条自然口语提问（含期望 tag）+ 20 条负样本 |
| `eval/run-eval.py` | **RAG 检索质量评测**：Hit@K / MRR / 阈值敏感性，输出 `docs/rag-eval-report.md` |

---

## 1.4 界面预览

> 截图在**离线桩模式**（未配置 API Key）下采集 —— 零依赖即可复现：
> `./run.sh` 后访问 <http://localhost:8080>，或跑 `node scripts/ui-shot.js` 自动重截
> （用本机 Chrome + puppeteer-core，无头模式）。

**录入与分析**：填表录入面试 → 左侧历史记录 → 右侧详情。分析完成后，弱项会**回写到题目卡片**上（红框标注）。

![录入与历史](docs/screenshots/01-overview.png)

![详情与分析](docs/screenshots/02-detail-report.png)

**知识库（RAG）**：文档列表带片段数与删除按钮，删除会连带清掉该文档的全部向量片段。

![知识库](docs/screenshots/03-knowledge-base.png)

---

## 2. 接口清单

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/chat?q=xxx` | 模型联通性冒烟 |
| GET | `/api/llm/mode` | 当前模式：`deepseek` / `stub` |
| POST | `/api/interviews` | 录入一次面试（含若干题目） |
| GET | `/api/interviews` | 面试记录列表 |
| GET | `/api/interviews/{id}` | 面试详情（含题目） |
| POST | `/api/interviews/analyze/{recordId}` | 弱项分析 + 补强建议（**报告自动落库**） |
| GET | `/api/analyses?recordId={id}` | 某条面试的历史分析列表 |
| GET | `/api/analyses/{analysisId}` | 回看某次分析的完整报告 |
| GET | `/api/interviews/{id}/analysis-stream` | SSE 流式复盘（Markdown 直出，不落库） |
| GET | `/api/debug/memory?excludeRecordId=&limit=` | 查看注入给模型的「历史记忆」原文 |
| GET | `/api/debug/info` | 当前实例的库类型 / 连接串（脱敏）/ 各表数据量 |
| GET | `/api/export` | **全量导出**：所有面试记录 + 历史报告，一个可读 JSON（页面右上角「导出 JSON」按钮同款） |
| POST | `/api/mock/start` | 开始模拟面试（出题并建会话，返回 sessionId） |
| POST | `/api/mock/answer` | 作答 → LLM 打分 + 决定是否追问 |
| POST | `/api/mock/{sessionId}/finish` | 结束并生成总评（总分 / 优点 / 改进） |
| GET | `/api/mock/{sessionId}` | 整场回放 |
| POST | `/api/knowledge/upload` | 笔记入库（切分 → embedding → pgvector） |
| GET | `/api/knowledge/search?q=&topK=5` | 相似度检索 |
| GET | `/api/knowledge/docs` | 已导入文档目录 |
| DELETE | `/api/knowledge/docs/{docId}` | 删除文档及其全部 chunk |
| POST | `/api/interviews/agent-analyze/{recordId}` | 模型自主调用工具版分析（失败自动降级为普通分析） |
| GET | `/api/agent/tools/knowledge?q=` | 工具自检——直接检索知识库 |
| GET | `/api/agent/tools/weak-points?topic=` | 工具自检——查历史弱项 |
| GET | `/api/agent/tools/jd?jd=` | 工具自检——提炼 JD 要求 |

---

## 2.5 RAG 知识库 ✅ 已跑通

链路：**笔记 → 按标题/段落切分（一 chunk 一主题）→ bge-m3 embedding(1024 维) → pgvector → 相似度检索**。

### 启动方式

```bash
./run.sh                                  # 默认走 Ollama(bge-m3) 语义 embedding
RAG_EMBEDDING=stub ./run.sh               # 无 Ollama 时用哈希兜底 embedding，仍可跑通全链路
RAG_ENABLED=false ./run.sh                # 完全关闭 RAG（其他功能不受影响）
```

### 切分策略（踩坑后修正）

**先按 Markdown 标题切小节**，小节内再按段落合并到 600 字，超长才滑窗（重叠 100），代码块内不切。

为什么必须标题优先：最初按段落切分时，1439 字的示例笔记只切出 3 段、多个主题混在一起
—— 检索「缓存击穿」会串到 MySQL 段落。改成标题优先后切出 6 段，一 chunk 一主题。
**切分粒度对检索的影响，明显大于换 embedding 模型。**

### 检索质量评测：40 正样本 + 20 负样本

完整报告见 [`docs/rag-eval-report.md`](docs/rag-eval-report.md)，可一键复跑：
`python3 scripts/eval/run-eval.py`

| 指标 | 结果 |
|---|---|
| Hit@1（正确答案排第一） | **28/40 = 70.0%** |
| **Hit@3** | **38/40 = 95.0%** |
| Hit@5 | 38/40 = 95.0% |
| **MRR** | **0.821** |

评测设计（这三条决定了结果可信度）：

- 语料是 **30 个知识点**的八股笔记；提问是 **40 条自然口语提问**，刻意避开文档标题用词
  （例如问「堆开到 64G、要求停顿 10ms 以内该选哪种回收器」，而不是问「G1 收集器」）
- 另设 **20 条负样本**（语料中完全不存在的主题，如 Kafka / K8s / Elasticsearch），用于测假阳性
- 命中判定基于文档 `tags` 自动完成，无人工标注介入

**最有价值的结论来自负样本**：

| 阈值 | 正样本 Top1 通过 | 负样本误判 |
|---|---|---|
| ≥55% | 92.5% | 40.0% |
| ≥58% | 82.5% | 15.0% |
| ≥60% | 72.5% | **0%** |
| ≥65% | 22.5% | 0% |

负样本（毫不相关的主题）最高能拿到 **59.9%**，比正样本最低分（40.1%）还高 20 个百分点。
**不存在可用的阈值工作点** —— 想让误判归零就要损失四分之一的正样本。
所以这类系统的验收标准只能是排序指标（Hit@K / MRR），不能写成「相似度 > 0.7 即命中」。

> 早期版本用 6 条**与文档标题高度重合**的提问做过验证，得到「6/6 命中、相似度 61%~79%」。
> 那个测试没有负样本对照，**说明不了效果**，现已降级为回归冒烟（`scripts/verify-all.sh` 里的命中断言）。
> 检索效果以本节和 `docs/rag-eval-report.md` 为准。

> 切换 embedding 后**必须重新导入笔记**：旧 chunk 是用另一个模型编码的，
> 留在库里会和新向量不同空间，检索结果会乱。删文档用 `DELETE /api/knowledge/docs/{docId}`。

### RAG 增强分析的效果

同一条面试记录，开启知识库前后，模型给出的盲区粒度明显不同：

| | 盲区示例 |
|---|---|
| 无知识库 | 「G1 的 Region 划分、Young/Old 回收流程、Mixed GC 触发条件」 |
| **有知识库** | 「G1 关键调优参数：`-XX:MaxGCPauseMillis`（默认 200ms）、`-XX:InitiatingHeapOccupancyPercent`（默认 45%）」<br>「布隆过滤器的误判率和元素无法删除的局限性」 |

后者的细节**只存在于笔记里** —— 证明检索到的片段确实进了 Prompt。

### 环境准备（需在你自己的终端执行）

系统级安装要写 `/opt/homebrew`，受限环境装不了，请手动跑：

```bash
bash scripts/setup-rag.sh
```

等价于：

```bash
brew install postgresql@17 pgvector     # 注意是 17，不是 16（原因见下）
brew services stop postgresql@16 2>/dev/null || true
brew services start postgresql@17
createdb interview_vector
psql -d interview_vector -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

> ⚠️ **必须用 postgresql@17**：brew 的 pgvector 0.8.6 只为 `postgresql@17` / `@18` 编译扩展文件，
> PG16 的 `share/postgresql@16/extension/` 里没有 `vector.control`，
> 会报 `extension "vector" is not available`。已验证扩展文件位于
> `/opt/homebrew/share/postgresql@17/extension/vector.control`。

Ollama 用官方包（brew 那版要连带拉 python@3.14 + mlx，大且容易卡）：

```bash
curl -L --progress-bar -o /tmp/Ollama-darwin.zip https://ollama.com/download/Ollama-darwin.zip
unzip -q /tmp/Ollama-darwin.zip -d /Applications
# 首次启动前先建好模型目录，否则可能报 remove ~/.ollama/models/manifests: operation not permitted
mkdir -p ~/.ollama/models/manifests ~/.ollama/models/blobs
/Applications/Ollama.app/Contents/Resources/ollama serve &
/Applications/Ollama.app/Contents/Resources/ollama pull bge-m3    # 1.2GB
```

> ⚠️ **YAML 缩进坑（踩过）**：`ollama:` 必须是 `spring.ai` 的子节点。
> 一旦缩进成 `spring.ai.openai` 的子节点，`spring.ai.ollama.*` 全部失效，
> embedding 会静默退回 Ollama 默认模型 `mxbai-embed-large`，报
> `model "mxbai-embed-large" not found`。改完 yml 记得 `mvn package` 重启。

若 Postgres 用户不是当前 macOS 用户，启动时覆盖：`VECTOR_DB_USER=xxx VECTOR_DB_PASSWORD=yyy ./run.sh`

> ⚠️ **镜像坑**：国内 brew 镜像（阿里/清华）bottle 不全，会出现
> `404 ... gettext-1.0.arm64_tahoe.bottle.2.tar.gz` / `python@3.14` 这类报错，
> 导致 postgresql / ollama 装到一半失败。有梯子时**不要用镜像**：
> `unset HOMEBREW_BOTTLE_DOMAIN` 并删掉 `~/.zshrc` 里那行 export，再重跑脚本即可（脚本是幂等的，已装的会跳过）。
> `ollama` 的 brew 配方要拉 `python@3.14 + mlx`（体积大），脚本在 brew 失败时会自动改用官方 zip。

### 验证

```bash
curl -X POST http://localhost:8080/api/knowledge/upload \
  -H "Content-Type: application/json" \
  -d @scripts/sample-note.json
curl "http://localhost:8080/api/knowledge/search?q=G1%20回收流程&topK=3"
```

### 页面入口

首页底部「**知识库（RAG）**」区块：粘贴笔记（或点「填入示例片段」）→ 入库 → 用检索框验证召回，
结果按相似度排序展示。环境没就绪时该区块会显示 503 提示，**不影响上面录入/分析功能**。

### RAG 增强分析

`AnalysisService` 在生成复盘报告前，会先用「岗位 + 题目标签」检索知识库，把命中的 3 个片段塞进 Prompt，
让模型判断回答是否覆盖了笔记里的要点。**知识库不可用时静默降级为空**，分析照常完成（日志打一条 warn）。

---

## 2.6 Function Calling（模型自主调用工具）

三个工具，由模型自己决定调不调、调几次（不是我们在代码里写死流程）：

| 工具 | 作用 |
|---|---|
| `searchKnowledgeBase(query)` | 查候选人笔记里有没有覆盖某个知识点 |
| `searchWeakPoints(topic)` | 查历史上这个方向是否**已经暴露过弱项**（判断是否老问题重犯） |
| `getJdRequirements(jd)` | 从 JD 提炼关键技术要求 |

- 代码：`agent/InterviewTools`（`@Tool` 注解）、`agent/AgentAnalysisService`
- 入口：`POST /api/interviews/agent-analyze/{recordId}`，页面「**Agent 分析**」按钮
- **失败自动降级**为不带工具的普通分析，不会出现"点了没反应"
- 自检端点：`/api/agent/tools/*` 可绕过模型单独验证每个工具

已用 `--logging.level.org.springframework.ai=DEBUG` 确认模型真的发起了调用：

```
DefaultToolCallingManager : Executing tool call: getJdRequirements
MethodToolCallback        : Successful execution of tool: getJdRequirements
DefaultToolCallingManager : Executing tool call: searchWeakPoints   ×2
```

### 设计要点

- 业务数据仍在 H2，向量数据走独立 Postgres 数据源；**该数据源刻意不注册为 Spring bean**，
  否则会被 JPA 当成主数据源（曾导致 Postgres 没起时整个应用起不来）。
- `VectorStore` 用 `@Lazy`：Ollama / Postgres 没启动时，其他功能照常可用，
  只有调用 RAG 接口才报 **503** 并提示怎么修。
- 多个模型提供方并存，必须用 `spring.ai.model.chat=openai` / `spring.ai.model.embedding=ollama` 指定，
  否则 `ChatClient.Builder` 会因多个候选而失败。
- 不想要 RAG 时：`RAG_ENABLED=false ./run.sh`。

### 录入示例

```bash
curl -X POST http://127.0.0.1:8080/api/interviews \
  -H "Content-Type: application/json" \
  -d '{
    "company": "某互联网公司",
    "position": "Java 后端工程师",
    "interviewDate": "2026-09-20",
    "result": "挂了",
    "jd": "负责交易核心链路，要求 Java 并发、JVM 调优、MySQL 索引、Redis 缓存",
    "questions": [
      {
        "question": "讲一下 JVM 垃圾回收机制和线上用的收集器",
        "myAnswer": "大概说了分代回收，具体收集器记不清了",
        "feedback": "对 G1 的工作流程讲得不清晰",
        "score": 4,
        "tags": "JVM,GC"
      }
    ]
  }'
```

### 弱项分析返回示例（真实模型）

```json
{
  "summary": "原理题普遍答得浅，工程题有细节。",
  "goodQuestions": [{ "question": "…", "reason": "…" }],
  "weakQuestions": [{ "question": "…", "reason": "…" }],
  "knowledgeGaps": ["G1 回收流程", "缓存击穿"],
  "topPriorities": [
    { "point": "G1 回收流程", "practiceAdvice": "画一遍 G1 的 Young GC / Mixed GC 时序图，讲给同事听 10 分钟" }
  ]
}
```

分析完成后，弱项会**自动回写到 `interview_question.weak_points`**，为后续检索/统计做准备。

---

---

## 2.7 模拟面试（多轮追问 + 打分）

流程：**贴 JD → 出题 → 逐题作答 → 答得浅就被追问 → 答到位才进下一题 → 结束生成总评**。全程落库。

| 接口 | 说明 |
|---|---|
| `POST /api/mock/start` | 按 JD 出题并建会话，返回 `sessionId` + 题目列表 |
| `POST /api/mock/answer` | `{sessionId, questionIndex, answer}` → `score`(0-100) + `feedback` + `followUp` |
| `POST /api/mock/{id}/finish` | 汇总：`totalScore` + `comment` + `strengths` + `improvements` |
| `GET /api/mock/{id}` | 整场回放（面试官/候选人每轮发言 + 得分） |

规则约束（写在 Prompt 里，模型遵守）：
- 回答浮于表面 → 给一道追问，顺着没讲清的点继续问
- 回答到位 → `followUp` 返回空字符串，前端据此出现「下一题」
- **同一题最多追问两次**，第三次回答必须以结束本题收尾（避免无限追问）

页面「**模拟面试**」区块：填 JD / 重点方向 / 题数 → 开始 → 逐题作答，实时看到得分与追问。

真实模型实测（3 题会话）：

```
第 1 题 答「大概就是用 synchronized 保证线程安全吧」→ 得分 20 → 追问 ReentrantLock 多了哪些能力
追问轮 答出可中断/超时/公平锁/Condition      → 得分 85 → 本题结束
第 2、3 题                                    → 各 55 分
结束汇总 → 总分 55，给出 3 条优点 + 3 条具体改进建议
回放   → 10 轮对话完整保留（含追问）
```

---

## 2.8 SSE 流式输出 + 会话记忆

### SSE 流式复盘

```bash
curl -N http://localhost:8080/api/interviews/1/analysis-stream
```

- 返回 `text/event-stream`，`Transfer-Encoding: chunked`，按 token 逐段推送
- 为什么用 **Markdown 而不是 JSON**：流式下没法做结构化校验（JSON 中途是残缺的），
  所以流式走 Markdown 直出，结构化报告仍由 `/api/interviews/analyze/{id}` 提供
- 流式结果**不落库**（页面有提示）；要存档用「分析弱项」
- 页面「**流式复盘**」按钮：`fetch` + `ReadableStream` 逐字渲染（`EventSource` 只支持 GET，
  也能用，但用 fetch 更方便控制中断与错误处理）

### 会话记忆

分析时自动带上「**其他历次面试的复盘结论 + 当时盲区**」（最多 3 条），让模型识别老问题。
实测效果 —— 同一场面试，开记忆后模型会主动点名：

> 「Mixed GC 等核心机制完全未答出，**与历史复盘中的盲区完全重合，属于同一问题反复出现且未补强**」

自检端点（直接看注入给模型的原文）：

```bash
curl "http://localhost:8080/api/debug/memory?excludeRecordId=1&limit=3"
```

**为什么没用 Redis**：记忆内容本来就以报告形式存在 `interview_analysis` 表里，直接查即可；
Redis 的价值在多实例共享缓存、高频读写场景，这个单机 MVP 用不上。
想换只需替换 `MemoryService`（对外只暴露 `recentInsights`），业务代码不用动。
开关：`MEMORY_ENABLED=false ./run.sh`。

---

## 2.9 Docker Compose 全量编排

### 一键起全栈

```bash
export DEEPSEEK_API_KEY=sk-xxx
docker compose up -d --build
docker compose exec ollama ollama pull bge-m3      # 首次要拉模型（1.2GB）
# 浏览器打开 http://localhost:8080
```

三个服务：`postgres`（业务表 + 向量表同库）、`ollama`（本地 embedding）、`app`。
早先的 `mysql` / `redis` 收进 `legacy` profile，默认不启动（`docker compose --profile legacy up -d` 才起）。

### 架构

```mermaid
flowchart LR
  U[浏览器 / curl] -->|HTTP :8080| APP[interview-agent<br/>Spring Boot 3.5 + Java 21]
  APP -->|JDBC<br/>业务表 + vector_store| PG[(PostgreSQL + pgvector<br/>:5432)]
  APP -->|HTTP :11434<br/>embedding bge-m3| OL[Ollama]
  APP -->|HTTPS<br/>chat / tool calling| DS[DeepSeek API]
```

数据流：**面试记录/报告/模拟面试 → PostgreSQL**；**笔记切分后的 chunk + 1024 维向量 → 同一个 PostgreSQL 的 `vector_store` 表**（HNSW cosine 索引）；**embedding 由本地 Ollama 算**，只有对话/工具调用出网到 DeepSeek。

### Dockerfile 要点

- **多阶段构建**：构建镜像用 `maven:3.9-eclipse-temurin-21`，运行镜像只留 `eclipse-temurin:21-jre`
- 先 `COPY pom.xml` + `dependency:go-offline` 再拷源码 → 改代码不会重下依赖
- 构建阶段写了阿里云 Maven 镜像的 `settings.xml`，国内构建快很多
- **非 root 运行**（uid 1001）、`MaxRAMPercentage=75`、时区 `Asia/Shanghai`
- 健康检查用 bash 的 `/dev/tcp` 探测（temurin 镜像里没有 curl）

### 容器里为什么用单库

业务表和向量表都放在同一个 PostgreSQL 里 —— 少一个组件、少一份备份、事务边界更清晰。
本地开发的「H2 存业务 + PG 存向量」是双数据源设计（见 `VectorStoreConfig`），
容器里把两者指向同一个 URL 即可，**代码零改动**：

```
BIZ_DB_URL    = jdbc:postgresql://postgres:5432/interview_vector    # 业务表
VECTOR_DB_URL = jdbc:postgresql://postgres:5432/interview_vector    # 向量表（同一个库）
```

### 本地模拟容器（不用 Docker）

```bash
bash scripts/run-postgres-local.sh      # 与 compose 里 app 服务完全相同的环境变量
```

### 已验证 / 未验证

| 项 | 状态 |
|---|---|
| `application-postgres.yml`：PG 上自动建 6 张业务表（含 `position` 关键字转义） | ✅ 实测 |
| PG 上跑通录入 / 分析 / RAG 检索 / 会话记忆 | ✅ 实测 |
| `docker-compose.yml` YAML 语法与 env 契约 | ✅ 校验通过 |
| **镜像构建与 `docker compose up`** | ⚠️ **未实测**（开发机没装 Docker） |

---

## 2.10 缓存与降级设计

**缓存解决什么问题**：LLM 调用按 token 收费且单次耗时 3~10 秒，同一份内容重复分析既慢又烧钱。

| 机制 | 说明 |
|---|---|
| 分析结果缓存 | key = `prompt版本` + `记录id` + `内容指纹`，内容相同**不重复调用模型** |
| 自动失效 | 指纹覆盖题目 / 回答 / 反馈 / 打分，任一变化 key 自动变，无需手工清理 |
| Prompt 版本 | 改 System Prompt 时递增 `PROMPT_VERSION`，旧缓存自然失效 |
| 检索缓存 | 检索结果缓存 `kbsearch:*`，入库 / 删文档后按前缀批量失效 |
| 防穿透 | 空结果也写入短 TTL 缓存，避免同一无效请求反复打到模型 |

**降级原则**：Redis、向量库、Ollama、模型 API 都是**可降级组件**：

| 组件不可用 | 表现 |
|---|---|
| Redis | 缓存读写静默失败，主流程照常执行（指标 `result="error"` 上升；连续失败只告警一次，避免日志风暴） |
| 向量库 / Ollama | 检索 503，分析退化为不带知识上下文；录入与报告回看不受影响 |
| 模型 API（无 Key） | 自动切离线桩模式，除 LLM 外的链路全部可跑 |

**为什么它们不参与存活判定**：Redis / MQ 挂掉时应用仍然可用，
所以 `/actuator/health` 不会因这两个组件变成 DOWN —— 否则会触发误告警和不必要的重启。

已验证的两条路径：

```text
无 Redis：接口全部 200，app_cache_requests{result="error"} 上升，主流程不受影响
有 Redis：第 1 次分析 miss → 写入；第 2 次相同请求 hit = 1，不再调用模型
```

---

## 2.11 异步分析任务

**解决什么问题**：首次分析要调模型，单次 3~10 秒；同步接口会让 HTTP 请求一直挂着 —— 占用连接、前端转圈、还有超时风险。

| 接口 | 说明 |
|---|---|
| `POST /api/analysis/tasks` | 提交任务，**立即返回 202** 与 `taskId`，不等模型 |
| `GET /api/analysis/tasks/{taskId}` | 查状态；`SUCCESS` 时直接把报告带回来 |
| `GET /api/analysis/tasks?recordId=` | 最近 20 条任务 |
| `POST /api/analysis/tasks/{taskId}/retry` | 失败任务重试（taskId 不变，便于追踪） |

状态机：`PENDING → RUNNING → SUCCESS / FAILED`

同步接口 `POST /api/interviews/analyze/{id}` **仍然保留**：演示、自检，以及**命中缓存时**
（本来就毫秒级）走同步更直接。

**三个关键设计**：

1. **先落库，再投递** —— 反过来做的话，消息发出去了但写库失败，消费者查不到任务，
   消息等于丢失且无从追踪。先落库的最坏情况是任务卡在 `PENDING`，可以扫描出来补偿。

2. **幂等在数据库层**：
   ```sql
   UPDATE analysis_task SET status='RUNNING' WHERE task_id = ? AND status = 'PENDING'
   ```
   消息重放或消费端重连时，第二条抢不到 `PENDING` 状态（影响 0 行）就直接跳过 ——
   不会把同一份内容分析两次，也就不会重复消耗 token。

3. **状态变更独立于业务事务**（`REQUIRES_NEW`）—— 分析失败时业务数据该回滚就回滚，
   但"这次失败了"这件事必须记下来，否则任务会永远停在 RUNNING。

**降级**：`app.async.mode=mq` 时投递 RabbitMQ，发送失败自动转本地线程池；
线程池队列满时用 `CallerRunsPolicy` 把压力还给调用方（HTTP 变慢），形成背压而不是静默丢任务。

实测（真实 RabbitMQ 容器）：

```text
MQ 正常：提交 202（0.09s）  → 通道 mq   → SUCCESS
停掉 MQ：提交 202（0.014s）→ 通道 pool → 仍然 SUCCESS（自动降级）
重复投递同一条消息：skipped +1，且报告没有重复生成
```

---

## 3. 数据存储

### 先搞清楚数据在哪个库（多实例容易搞混）

页面顶部会直接显示当前实例用的库和数据量，例如：

```
库：PostgreSQL · 记录 1 · 报告 2 · 笔记 1 · embedding ollama
```

对应接口 `GET /api/debug/info`（连接串已脱敏）。**不同启动方式用不同的库**：

| 启动方式 | 数据库 | 备注 |
|---|---|---|
| `./run.sh`（默认，H2 模式） | `data/interviewdb.mv.db` | 零依赖，适合快速试用 |
| `bash scripts/run-postgres-local.sh` | PostgreSQL `interview_vector` | **真实数据在这里** |
| `SPRING_PROFILES_ACTIVE=mysql ./run.sh` | MySQL `interview_agent` | 需自备 MySQL |
| `docker compose up -d` | 容器内 PostgreSQL | 见 2.9 节 |

> ⚠️ H2 是**单进程独占**的文件库：同一个库不能同时被两个实例打开。
> 起新实例请换端口或换库，否则会报 `Database may be already in use`。

- **默认 H2**（MySQL 兼容模式，文件落盘 `./data/`），零依赖。`spring.jpa.ddl-auto=update` 自动建表。
  - H2 控制台：<http://127.0.0.1:8080/h2-console>，JDBC URL 填 `jdbc:h2:file:./data/interviewdb`，账号 `sa`，密码空。
- **切真 MySQL**：先 `docker compose up -d mysql`，再

```bash
SPRING_PROFILES_ACTIVE=mysql MYSQL_PASSWORD=root ./run.sh
```

  表结构见 `db/schema-mysql.sql`（也可依赖 `ddl-auto` 自动生成）。

### 表结构

```
interview_record   (id, company, position, interview_date, jd, result, notes, created_at)
interview_question (id, record_id, question, my_answer, feedback, score, tags, weak_points, created_at)
interview_analysis (id, record_id, model, summary, report_json, created_at)   # 分析报告快照，每次分析一条
mock_session      (id, target_jd, focus, status, total_score, summary, created_at)
mock_turn         (id, mock_session_id, turn_index, role, content, score, feedback, created_at)
```

**生成内容的保存情况**：面试记录与题目 ✅ 全部落库；弱项原因 ✅ 回写到 `interview_question.weak_points`；
分析报告全文 ✅ 以 JSON 存进 `interview_analysis.report_json`，页面「历史分析」可随时回看；
按 JD 生成的模拟题 ✅ 已落库（`mock_session` / `mock_turn`）。

### 关于「本机没装数据库」

H2 是**嵌入式数据库**，以 jar 依赖形式跑在应用进程里，**不需要安装任何数据库软件**；数据落在 `data/interviewdb.mv.db`
（二进制块格式，不是明文）。想直接看/备份/迁移数据，用页面右上角「导出 JSON」或：

```bash
curl http://localhost:8080/api/export -o interview-export.json
```

导出的 JSON 含 `interviews`（公司/岗位/JD/题目/回答/反馈/打分/标签/弱项）与 `analyses`（每份完整报告）。

> 隐私提醒：面试记录含公司、JD 和你的真实回答，属于敏感内容。当前**无鉴权、未加密**，
> `data/` 已在 `.gitignore` 中（不会进 git），请不要把服务暴露到公网。

---

## 4. 目录结构

```
interview-agent/
├── pom.xml
├── docker-compose.yml          # 全栈编排：postgres(pgvector) + ollama + app
├── Dockerfile                  # 多阶段构建，非 root 运行
├── .dockerignore
├── run.sh                      # 本地启动（自动用工作区内的隔离版 Maven）
├── db/init-vector.sql          # 容器首启启用 vector 扩展
├── db/schema-mysql.sql         # 真 MySQL 建表语句
├── scripts/
│   ├── smoke.sh / verify-all.sh / ui-smoke.js / setup-rag.sh / run-postgres-local.sh
│   └── eval/                   # RAG 检索质量评测：corpus.md / queries.json / run-eval.py
├── docs/rag-eval-report.md     # 评测报告（由 run-eval.py 自动生成）
└── src/main/java/com/dreamback/interviewagent/
    ├── controller/             # ChatController / InterviewController / MockController
    ├── service/                # InterviewService / AnalysisService / MockInterviewService
    ├── llm/                    # LlmService 接口 + DeepSeek 实现 + Stub 兜底
    ├── entity|repository|dto|config|web
```

**关键设计**：`LlmService` 只有两个实现。未配置 Key 或 `LLM_STUB=true` 时走 `StubLlmService`，
它把规则化结果**序列化后再走一遍 `BeanOutputConverter` 反序列化**，因此「JSON 解析 → 落库」这条路径在离线状态下同样被真实覆盖，不会出现「接上 Key 才发现解析挂了」。

---

## 5. 用 IDEA 打开

`File → Open` 选中 `interview-agent` 目录即可，IDEA 会自动识别 Maven 工程（无需你手动建项目）。

如果你**坚持自己用 IDEA 生成骨架**，勾选这些：

- Spring Boot 版本 3.5.x，Java 21，Maven，Jar 打包
- Dependencies：Spring Web、Spring Data JPA、Validation、Lombok、Spring Boot Actuator、H2 Database、MySQL Driver
- Spring AI 在 Initializr 里不一定能搜到（取决于版本），若没有就手动在 `pom.xml` 加 `spring-ai-starter-model-openai` + `spring-ai-bom`（见本工程 pom）

---

## 6. 开发进度

| 模块 | 状态 |
|---|---|
| 录入 → 分析 → 落库闭环 | ✅ |
| RAG 知识库（切分 / 检索 / 增强分析） | ✅ |
| Function Calling（模型自主调用工具） | ✅ |
| 模拟面试（多轮追问 + 打分 + 汇总 + 回放） | ✅ |
| SSE 流式复盘 + 跨记录会话记忆 | ✅ |
| Docker Compose 全量编排 | ✅ |

---

## 7. 已知限制 & 排错

- **`position` 是 SQL 关键字**，实体里用反引号转义（`` @Column(name = "`position`") ``，MySQL / H2-MySQL 模式均已验证）。
- **启动端口不是 8080**：检查是否设置了 `SERVER_PORT` / `SERVER__PORT` 环境变量，Spring Boot 宽松绑定会用它们覆盖 `server.port`（某些 IDE / 沙箱会注入）。启动时显式加 `--server.port=8080` 即可。
- **stub 模式下 `/chat` 与模拟题返回的是规则化假数据**，仅用于验证链路；真实结论必须配 Key。
- 单用户、无鉴权，不要直接暴露到公网。
- `/api/debug/*` 会暴露连接串（已脱敏密码）与注入给模型的 prompt 原文；对外部署时设 `DEBUG_ENDPOINTS=false` 关闭。

---

## 8. 开发与贡献

```bash
./mvnw test                    # 单元测试（离线，无需任何外部服务）
./mvnw package -DskipTests     # 打包
bash scripts/verify-all.sh      # 端到端自检（需服务已启动）
```

CI：`.github/workflows/build.yml`，push / PR 时以 `LLM_STUB=true RAG_ENABLED=false` 跑 `./mvnw verify`，不依赖任何密钥与外部服务。

## 9. License

[MIT](LICENSE) © 2026 dreamback2025
