#!/usr/bin/env bash
# 端到端冒烟：模式 -> 录入面试 -> 列表 -> 详情 -> 弱项分析 -> 按 JD 出 5 道模拟题
set -e
BASE=${BASE:-http://127.0.0.1:8080}
# 本机若设置了 HTTP_PROXY，curl 默认会走代理导致连不上 localhost
export NO_PROXY='127.0.0.1,localhost'
export no_proxy='127.0.0.1,localhost'
CURL="curl -s"

echo "== 1) 查看 LLM 模式 =="
$CURL "$BASE/api/llm/mode"; echo

echo "== 2) 录入一条面试记录 =="
RECORD=$($CURL -X POST "$BASE/api/interviews" \
  -H "Content-Type: application/json" \
  -d '{
    "company": "某互联网公司",
    "position": "Java 后端工程师",
    "interviewDate": "2026-09-20",
    "result": "挂了",
    "jd": "负责交易核心链路开发，要求熟悉 Java 并发、JVM 调优、MySQL 索引与 Redis 缓存设计。",
    "notes": "二面，整体偏原理追问。",
    "questions": [
      {
        "question": "讲一下 JVM 垃圾回收机制和你们线上用的收集器",
        "myAnswer": "大概说了分代回收，具体收集器记不清了",
        "feedback": "对 G1 的工作流程讲得不清晰",
        "score": 4,
        "tags": "JVM,GC"
      },
      {
        "question": "MySQL 索引失效有哪些场景",
        "myAnswer": "说了最左前缀，举了 like 前缀通配符的例子",
        "feedback": "回答较完整，补充了隐式类型转换",
        "score": 8,
        "tags": "MySQL,索引"
      },
      {
        "question": "Redis 缓存穿透和雪崩怎么解决",
        "myAnswer": "布隆过滤器，过期时间加随机值",
        "feedback": "只答了一半，没讲击穿",
        "score": 6,
        "tags": "Redis,缓存"
      }
    ]
  }')
echo "$RECORD"

ID=$(echo "$RECORD" | grep -o '"id":[0-9]*' | head -1 | cut -d: -f2)
if [ -z "$ID" ]; then echo "录入失败，未拿到 id" >&2; exit 1; fi
echo "录入成功，id=$ID"

echo "== 3) 列表 =="
$CURL "$BASE/api/interviews"; echo

echo "== 4) 详情 =="
$CURL "$BASE/api/interviews/$ID"; echo

echo "== 5) 弱项分析 =="
$CURL -X POST "$BASE/api/interviews/analyze/$ID"; echo

echo "== 6) 分析后回查 weak_points 是否落库 =="
$CURL "$BASE/api/interviews/$ID" | grep -o '"weakPoints":"[^"]*"'; echo

echo "== 7) 按 JD 生成 5 道模拟题 =="
$CURL -X POST "$BASE/api/mock/start" \
  -H "Content-Type: application/json" \
  -d '{"jd":"招聘 Java 后端：要求 Java 并发、JVM、MySQL 索引、Redis 缓存、Spring 原理，有高并发交易系统经验优先。","count":5,"focus":"并发"}'
echo
