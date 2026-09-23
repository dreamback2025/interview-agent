#!/usr/bin/env bash
# 全功能自检：录入/分析/报告 + RAG 检索 + 工具调用 + 模拟面试 + 流式与记忆
# 用法：bash scripts/verify-all.sh          （默认 http://127.0.0.1:8080）
#      BASE=http://127.0.0.1:8081 bash scripts/verify-all.sh
export NO_PROXY='127.0.0.1,localhost'
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
BASE=${BASE:-http://127.0.0.1:8080}
HERE="$(cd "$(dirname "$0")" && pwd)"

# Python 只用于解析 JSON，任意 3.x 均可；优先用环境变量 PY 覆盖
if [ -z "${PY:-}" ]; then
  if command -v python3 >/dev/null 2>&1; then PY=python3
  elif command -v python >/dev/null 2>&1; then PY=python
  else echo "本脚本需要 python3 解析 JSON，请先安装" >&2; exit 1
  fi
fi
command -v "$PY" >/dev/null 2>&1 || PY=python3

pass=0; fail=0
# 用 ASCII 标记与分隔符，避免某些终端/重定向把中文内容转码后显示错乱
ok()  { echo "  [PASS] $1"; pass=$((pass+1)); }
bad() { echo "  [FAIL] $1"; fail=$((fail+1)); }
J() { "$PY" -X utf8 -c "import sys,json;d=json.load(sys.stdin);$1" 2>/dev/null; }

echo "===== 0. 服务与模型 ====="
H=$(curl -s -m 5 "$BASE/actuator/health")
[ "$H" = '{"status":"UP"}' ] && ok "健康检查 UP" || bad "健康检查异常: $H"
MODE=$(curl -s -m 5 "$BASE/api/llm/mode" | J "print(d.get('mode'))")
[ "$MODE" = "deepseek" ] && ok "LLM 模式 = deepseek" || bad "LLM 模式 = $MODE（应为 deepseek）"

echo "===== 1. 录入 / 列表 / 详情 ====="
REC=$(curl -s -m 20 -X POST "$BASE/api/interviews" -H 'Content-Type: application/json' -d '{
  "company":"自检公司","position":"Java 后端","interviewDate":"2026-09-23","result":"挂了",
  "jd":"Java 并发、JVM、MySQL 索引、Redis 缓存",
  "questions":[
    {"question":"讲一下 G1 回收流程","myAnswer":"分代回收，细节记不清","feedback":"追问 Mixed GC 没答上","score":4,"tags":"JVM,GC"},
    {"question":"MySQL 索引失效场景","myAnswer":"最左前缀 + like 前缀通配符 + 隐式转换","feedback":"答得完整","score":8,"tags":"MySQL,索引"}
  ]}')
ID=$(echo "$REC" | J "print(d['id'])")
[ -n "$ID" ] && ok "录入成功 id=$ID" || bad "录入失败: $(echo $REC | head -c 120)"
[ "$(echo "$REC" | J "print(len(d['questions']))")" = "2" ] && ok "题目 2 条已保存" || bad "题目数不对"
CNT=$(curl -s -m 10 "$BASE/api/interviews" | J "print(len(d))")
[ "${CNT:-0}" -ge 1 ] && ok "列表返回 $CNT 条" || bad "列表为空"
[ "$(curl -s -m 10 "$BASE/api/interviews/$ID" | J "print(d['company'])")" = "自检公司" ] \
  && ok "详情字段正确" || bad "详情异常"

echo "===== 2. 弱项分析（普通 + Agent）====="
AN=$(curl -s -m 150 -X POST "$BASE/api/interviews/analyze/$ID")
[ -n "$(echo "$AN" | J "print(d['summary'][:5])")" ] && ok "普通分析返回报告" || bad "普通分析失败: $(echo $AN | head -c 120)"
[ "$(echo "$AN" | J "print(len(d['topPriorities']))")" -ge 1 ] && ok "含优先补强建议" || bad "缺少优先补强建议"
AG=$(curl -s -m 200 -X POST "$BASE/api/interviews/agent-analyze/$ID")
[ -n "$(echo "$AG" | J "print(d['summary'][:5])")" ] && ok "Agent 分析（含工具调用）返回报告" || bad "Agent 分析失败: $(echo $AG | head -c 120)"

echo "===== 3. 报告落库 / 历史回看 / 导出 ====="
HIS=$(curl -s -m 10 "$BASE/api/analyses?recordId=$ID")
HN=$(echo "$HIS" | J "print(len(d))")
[ "${HN:-0}" -ge 2 ] && ok "历史报告 $HN 份（两次分析都落库）" || bad "历史报告数异常: $HN"
AID=$(echo "$HIS" | J "print(d[-1]['id'])")
[ -n "$(curl -s -m 10 "$BASE/api/analyses/$AID" | J "print(d['report']['summary'][:5])")" ] \
  && ok "单份报告可回看" || bad "报告回看失败"
EX=$(curl -s -m 20 "$BASE/api/export")
[ "$(echo "$EX" | J "print(d['recordCount'])")" -ge 1 ] && ok "导出 JSON 正常（记录 $(echo "$EX" | J "print(d['recordCount'])") 条）" || bad "导出失败"

echo "===== 4. RAG 检索（pgvector + bge-m3）====="
for DID in $(curl -s -m 10 "$BASE/api/knowledge/docs" | J "print('\n'.join(x['docId'] for x in d))"); do
  curl -s -m 30 -X DELETE "$BASE/api/knowledge/docs/$DID" -o /dev/null
done
UP=$(curl -s -m 180 -X POST "$BASE/api/knowledge/upload" -H 'Content-Type: application/json' \
  --data-binary @"$HERE/sample-note.json")
CH=$(echo "$UP" | J "print(d['chunkCount'])")
[ "${CH:-0}" -ge 4 ] && ok "笔记入库，切出 $CH 段" || bad "入库失败: $(echo $UP | head -c 120)"

check_hit() {   # $1 查询词  $2 期望命中的标题前缀
  local out head score
  out=$(curl -s -m 60 --get --data-urlencode "q=$1" --data-urlencode "topK=1" \
        "$BASE/api/knowledge/search" | "$PY" -X utf8 -c "
import sys,json
r=json.load(sys.stdin)[0]
print(r['content'].split(chr(10))[0])
print(round(r['score']*100,1))
" 2>/dev/null)
  head=$(echo "$out" | head -1)
  score=$(echo "$out" | tail -1)
  if [[ "$head" == *"$2"* ]]; then
    ok "检索命中: [$1] -> [$head] score=${score}%"
  else
    bad "检索串味: [$1] -> [$head] expect=[$2]"
  fi
}
check_hit "G1 回收流程" "G1 垃圾收集器"
check_hit "缓存击穿怎么解决" "Redis"
check_hit "MySQL 索引失效" "MySQL 索引失效场景"
check_hit "Spring 循环依赖三级缓存" "Spring 循环依赖"

echo "===== 5. 工具自检 ====="
[ "$(curl -s -m 20 --get --data-urlencode "topic=JVM" "$BASE/api/agent/tools/weak-points" | J "print(len(d))")" -ge 1 ] \
  && ok "searchWeakPoints 有结果" || bad "searchWeakPoints 无结果"
[ "$(curl -s -m 20 --get --data-urlencode "jd=熟悉 Java 并发与 MySQL 索引" "$BASE/api/agent/tools/jd" | J "print(len(d))")" -ge 1 ] \
  && ok "getJdRequirements 有结果" || bad "getJdRequirements 无结果"
[ -n "$(curl -s -m 60 --get --data-urlencode "q=G1" "$BASE/api/agent/tools/knowledge" | J "print(d[0][:5])")" ] \
  && ok "searchKnowledgeBase 有结果" || bad "searchKnowledgeBase 无结果"

echo "===== 6. 模拟面试 ====="
MK=$(curl -s -m 150 -X POST "$BASE/api/mock/start" -H 'Content-Type: application/json' \
  -d '{"jd":"Java 并发、JVM、MySQL 索引、Redis 缓存","count":2,"focus":"并发"}')
SID=$(echo "$MK" | J "print(d['sessionId'])")
QN=$(echo "$MK" | J "print(len(d['questions']))")
[ -n "$SID" ] && ok "模拟面试会话已建 id=$SID questions=$QN" || bad "出题失败: $(echo $MK | head -c 120)"
ANS=$(curl -s -m 150 -X POST "$BASE/api/mock/answer" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":$SID,\"questionIndex\":0,\"answer\":\"大概就是用 synchronized 保证线程安全吧\"}")
SC=$(echo "$ANS" | J "print(d['score'])")
[ -n "$SC" ] && ok "作答已打分：$SC 分，追问=$(echo "$ANS" | J "print('有' if d['followUp'] else '无')")" || bad "打分失败"
curl -s -m 150 -X POST "$BASE/api/mock/answer" -H 'Content-Type: application/json' \
  -d "{\"sessionId\":$SID,\"questionIndex\":1,\"answer\":\"线程池核心参数 corePoolSize/maximumPoolSize/workQueue/handler，CPU 密集核数+1\"}" -o /dev/null
FIN=$(curl -s -m 150 -X POST "$BASE/api/mock/$SID/finish")
[ -n "$(echo "$FIN" | J "print(d['totalScore'])")" ] && ok "总评已生成：$(echo "$FIN" | J "print(d['totalScore'])") 分" || bad "总评失败"
TN=$(curl -s -m 10 "$BASE/api/mock/$SID" | J "print(len(d['turns']))")
[ "${TN:-0}" -ge 4 ] && ok "回放保留 $TN 轮对话" || bad "回放轮数异常: $TN"

echo "===== 7. 流式输出 / 会话记忆 ====="
ST=$(curl -s -N -m 150 "$BASE/api/interviews/$ID/analysis-stream" | tr -d '\r' | sed -n 's/^data://p' | tr -d '\n')
if [ -n "$ST" ]; then
  ok "SSE 流式复盘返回 ${#ST} 字"
  [[ "$ST" == *"复盘报告"* ]] && ok "流式内容含 Markdown 标题结构" || bad "流式内容缺少标题结构"
else
  bad "SSE 流式复盘无输出"
fi
CT=$(curl -s -N -m 20 -o /dev/null -D - "$BASE/api/interviews/$ID/analysis-stream" | tr -d '\r' | sed -n 's/^[Cc]ontent-[Tt]ype: //p')
[[ "$CT" == *"text/event-stream"* ]] && ok "Content-Type = text/event-stream" || bad "Content-Type 异常: $CT"
MEM=$(curl -s -m 20 "$BASE/api/debug/memory?excludeRecordId=$ID&limit=3")
[[ "$MEM" == *"历史记忆"* ]] && ok "会话记忆已注入（含其他记录的复盘结论）" || bad "会话记忆为空: $(echo $MEM | head -c 80)"

echo
echo "================================"
echo "  通过 $pass 项，失败 $fail 项"
echo "================================"
[ "$fail" -eq 0 ] || exit 1
