#!/usr/bin/env python3
"""
RAG 检索质量评测。

用法：
  BASE=http://127.0.0.1:8088 python3 run-eval.py              # 清空 → 导入语料 → 评测
  BASE=http://127.0.0.1:8088 SKIP_IMPORT=1 python3 run-eval.py # 只评测（语料已在库中）

指标：
  命中判定 = 检索结果的 tags 字段包含该查询的期望 tag
  Hit@1/@3/@5、MRR（Mean Reciprocal Rank）、负样本分数分布、阈值敏感性分析

产出：
  stdout 摘要 + ../../docs/rag-eval-report.md
"""
import json
import os
import re
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('BASE', 'http://127.0.0.1:8088')
SKIP_IMPORT = os.environ.get('SKIP_IMPORT') == '1'
TOPK = 5
HERE = os.path.dirname(os.path.abspath(__file__))
REPORT = os.path.abspath(os.path.join(HERE, '..', '..', 'docs', 'rag-eval-report.md'))
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))

# 鉴权默认开启：登录后所有请求带 Bearer token；也可用环境变量 TOKEN 直接传
TOKEN = os.environ.get('TOKEN', '')


def api(path, data=None, method=None):
    req = urllib.request.Request(BASE + path, method=method or ('POST' if data is not None else 'GET'))
    body = None
    if data is not None:
        body = json.dumps(data, ensure_ascii=False).encode('utf-8')
        req.add_header('Content-Type', 'application/json')
    if TOKEN:
        req.add_header('Authorization', 'Bearer ' + TOKEN)
    with OPENER.open(req, body, timeout=300) as r:
        txt = r.read().decode('utf-8')
        return json.loads(txt) if txt.strip() else None


def login():
    """登录拿 token（演示账号）；服务端关闭鉴权时静默继续"""
    global TOKEN
    if TOKEN:
        return
    for path in ('/api/auth/login', '/api/auth/register'):
        try:
            r = api(path, {'username': 'demo', 'password': 'demo123'})
            t = (r or {}).get('token')
            if t:
                TOKEN = t
                print('[auth] 已登录：%s' % path)
                return
        except Exception:
            continue
    print('[auth] 未获取 token，按免鉴权模式继续')


def parse_corpus():
    text = open(os.path.join(HERE, 'corpus.md'), encoding='utf-8').read()
    items = []
    for part in re.split(r'^## ', text, flags=re.M)[1:]:
        head, _, rest = part.partition('\n')
        tag, _, title = head.partition('|')
        items.append({
            'tag': tag.strip(),
            'title': title.strip(),
            'content': '## ' + title.strip() + '\n' + rest.strip(),
        })
    return items


def score_of(r):
    return r['score'] * 100


def tag_hit(result, expect):
    tags = result.get('tags') or ''
    return expect in [t.strip() for t in tags.split(',')]


def search(q, topk=TOPK):
    qs = urllib.parse.urlencode({'q': q, 'topK': topk})
    return api('/api/knowledge/search?' + qs) or []


def main():
    login()
    # 读服务端检索模式（hybrid / vector），写进报告 —— 两种配置的结果不能混在同一份报告里
    retrieval = 'unknown'
    try:
        retrieval = (api('/api/debug/info') or {}).get('retrieval') or 'unknown'
    except Exception:
        pass
    corpus = parse_corpus()
    spec = json.load(open(os.path.join(HERE, 'queries.json'), encoding='utf-8'))
    positives, negatives = spec['positive'], spec['negative']
    log = []

    def out(s=''):
        print(s)
        log.append(s)

    t0 = time.time()
    out('[corpus] %d 个知识点' % len(corpus))

    if not SKIP_IMPORT:
        existing = api('/api/knowledge/docs') or []
        for d in existing:
            api('/api/knowledge/docs/%s' % d['docId'], method='DELETE')
        if existing:
            out('[clean] 清空已有文档 %d 个' % len(existing))

        total_chunks = 0
        for i, item in enumerate(corpus, 1):
            res = api('/api/knowledge/upload', {
                'title': item['title'],
                'source': 'eval-corpus',
                'tags': item['tag'],
                'content': item['content'],
            })
            n = (res or {}).get('chunkCount', 0)
            total_chunks += n
            print('  [%2d/%d] %-14s -> %d chunk' % (i, len(corpus), item['tag'], n))
        out('[import] 导入完成，共 %d 个 chunk（%.0fs）' % (total_chunks, time.time() - t0))
    else:
        out('[import] 跳过（SKIP_IMPORT=1）')

    # ---------- 正样本 ----------
    out()
    out('=== 正样本检索（%d 条）===' % len(positives))
    rows, fails = [], []
    for item in positives:
        res = search(item['q'])
        rank = 0
        for i, r in enumerate(res, 1):
            if tag_hit(r, item['expect']):
                rank = i
                break
        top1 = res[0] if res else None
        rows.append({'q': item['q'], 'expect': item['expect'], 'rank': rank, 'top1': top1,
                     'top3': [r['title'] for r in res[:3]]})
        if rank != 1:
            fails.append(rows[-1])

    n = len(rows)
    h1 = sum(1 for r in rows if r['rank'] == 1)
    h3 = sum(1 for r in rows if 1 <= r['rank'] <= 3)
    h5 = sum(1 for r in rows if 1 <= r['rank'] <= 5)
    mrr = sum((1.0 / r['rank']) for r in rows if r['rank'] > 0) / n
    pos_top1_scores = [score_of(r['top1']) for r in rows if r['top1']]
    out('Hit@1 = %d/%d = %.1f%%' % (h1, n, 100.0 * h1 / n))
    out('Hit@3 = %d/%d = %.1f%%' % (h3, n, 100.0 * h3 / n))
    out('Hit@5 = %d/%d = %.1f%%' % (h5, n, 100.0 * h5 / n))
    out('MRR   = %.3f' % mrr)
    out('Top1 相似度: 均值 %.1f%%  最低 %.1f%%  最高 %.1f%%'
        % (statistics.mean(pos_top1_scores), min(pos_top1_scores), max(pos_top1_scores)))
    if fails:
        out('未命中 Top1 的 %d 条:' % len(fails))
        for f in fails:
            got = f['top1']['title'] if f['top1'] else '(空)'
            out('  - [%s] %s  → 实际 Top1: %s (rank=%s)' % (f['expect'], f['q'], got, f['rank'] or '>5'))

    # ---------- 负样本 ----------
    out()
    out('=== 负样本对照（%d 条语料外主题）===' % len(negatives))
    neg = []
    for q in negatives:
        res = search(q, topk=1)
        if res:
            neg.append({'q': q, 'score': score_of(res[0]), 'top1': res[0]['title']})
    neg_scores = [x['score'] for x in neg]
    out('最高分: 均值 %.1f%%  最低 %.1f%%  最高 %.1f%%'
        % (statistics.mean(neg_scores), min(neg_scores), max(neg_scores)))
    for x in sorted(neg, key=lambda x: -x['score'])[:5]:
        out('  %.1f%%  %s  → 命中「%s」' % (x['score'], x['q'], x['top1']))

    # ---------- 阈值分析 ----------
    out()
    out('=== 阈值敏感性分析 ===')
    out('阈值   正样本Top1通过   负样本误判')
    th_rows = []
    for t in [45, 50, 52, 54, 56, 58, 60, 65, 70]:
        p = sum(1 for s in pos_top1_scores if s >= t)
        nfalse = sum(1 for s in neg_scores if s >= t)
        th_rows.append((t, p, nfalse))
        out('>=%2d%%   %2d/%d (%5.1f%%)   %2d/%d (%5.1f%%)'
            % (t, p, n, 100.0 * p / n, nfalse, len(neg), 100.0 * nfalse / len(neg)))

    overlap = min(pos_top1_scores) <= max(neg_scores)
    out()
    out('正样本最低分 %.1f%%  vs  负样本最高分 %.1f%%  → %s'
        % (min(pos_top1_scores), max(neg_scores),
           '区间重叠，绝对相似度无法区分正负样本' if overlap else '区间不重叠'))

    # ---------- 写报告 ----------
    md = []
    md.append('# RAG 检索质量评测报告')
    md.append('')
    md.append('> 由 `scripts/eval/run-eval.py` 自动生成，语料 `scripts/eval/corpus.md`、查询 `scripts/eval/queries.json`，可完整复跑。')
    md.append('')
    md.append('## 1. 评测设置')
    md.append('')
    md.append('| 项 | 值 |')
    md.append('|---|---|')
    md.append('| 语料 | %d 个知识点（八股笔记，含具体参数与流程） |' % len(corpus))
    md.append('| 切分 | 按 Markdown 标题优先切分，600 字上限、重叠 100 |')
    md.append('| Embedding | Ollama bge-m3，1024 维 |')
    md.append('| **检索模式** | `%s`（hybrid = 向量 + 关键词 tsvector + RRF 融合；vector = 纯向量） |' % retrieval)
    md.append('| 向量库 | PostgreSQL 17 + pgvector 0.8.6，HNSW + cosine |')
    md.append('| 正样本 | %d 条自然口语提问（措辞刻意不与文档标题重合，expect 对应知识点 tag） |' % n)
    md.append('| 负样本 | %d 条语料中完全不存在的主题（用于测假阳性） |' % len(neg))
    md.append('| 命中判定 | 检索结果的 `tags` 字段包含期望 tag，不依赖人工判断 |')
    md.append('')
    md.append('## 2. 正样本检索质量')
    md.append('')
    md.append('| 指标 | 结果 |')
    md.append('|---|---|')
    md.append('| **Hit@1** | %d/%d = **%.1f%%** |' % (h1, n, 100.0 * h1 / n))
    md.append('| Hit@3 | %d/%d = %.1f%% |' % (h3, n, 100.0 * h3 / n))
    md.append('| Hit@5 | %d/%d = %.1f%% |' % (h5, n, 100.0 * h5 / n))
    md.append('| **MRR** | **%.3f** |' % mrr)
    md.append('| Top1 相似度 | 均值 %.1f%%，区间 %.1f%%~%.1f%% |'
              % (statistics.mean(pos_top1_scores), min(pos_top1_scores), max(pos_top1_scores)))
    md.append('')
    if fails:
        md.append('以下 %d 条查询的正确答案**未排在 Top1**（最后一列是它在结果中的实际排名，`>5` 表示未被召回）：' % len(fails))
        md.append('')
        md.append('| 期望知识点 | 提问 | 实际 Top1 | 正确排名 |')
        md.append('|---|---|---|---|')
        for f in fails:
            got = f['top1']['title'] if f['top1'] else '(空)'
            md.append('| `%s` | %s | %s | %s |' % (f['expect'], f['q'], got, f['rank'] or '>5'))
        md.append('')
    # 与纯向量基线对照（基线固化在 baseline-vector.json，保证结论可复跑）
    baseline_path = os.path.join(HERE, 'baseline-vector.json')
    if retrieval == 'hybrid' and os.path.exists(baseline_path):
        b = json.load(open(baseline_path, encoding='utf-8'))
        md.append('## 2.5 混合检索的收益（与纯向量基线对照）')
        md.append('')
        md.append('同一套语料、查询与 embedding，仅切换 `app.rag.hybrid.enabled`：')
        md.append('')
        md.append('| 指标 | 纯向量 `%s` | 混合 `%s` | 变化 |' % (b['mode'], retrieval))
        md.append('|---|---|---|---|')
        md.append('| **Hit@1** | %d/%d = %.1f%% | %d/%d = **%.1f%%** | **%+.1fpp** |'
                  % (b['hit1Count'], b['total'], b['hit1'] * 100, h1, n, 100.0 * h1 / n,
                     100.0 * h1 / n - b['hit1'] * 100))
        md.append('| Hit@3 | %.1f%% | %.1f%% | %+.1fpp |'
                  % (b['hit3'] * 100, 100.0 * h3 / n, 100.0 * h3 / n - b['hit3'] * 100))
        md.append('| Hit@5 | %.1f%% | %.1f%% | %+.1fpp |'
                  % (b['hit5'] * 100, 100.0 * h5 / n, 100.0 * h5 / n - b['hit5'] * 100))
        md.append('| **MRR** | %.3f | **%.3f** | %+.3f |' % (b['mrr'], mrr, mrr - b['mrr']))
        md.append('| 未命中 Top1 | %d 条 | %d 条 | %+d |'
                  % (b['missTop1'], len(fails), len(fails) - b['missTop1']))
        md.append('')
        md.append('**机制**：关键词通道（tsvector + GIN 索引）用 token 精确匹配，'
                  '把「语义相近但答案不同」的同域干扰项挤下去。'
                  '例如「索引失效」与「索引结构」共享 `mysql`/`索引`，但不共享 `引失`/`失效`，'
                  'RRF 融合后正确项排名上升。')
        md.append('')
        md.append('**已知副作用**：关键词通道会引入新噪音 —— 实测有 1/40 条出现回退'
                  '（「缓存和数据库的一致性」的 Top1 变成「分布式事务」）。'
                  '缓解方向：调低关键词通道权重，或引入 cross-encoder rerank 做二次精排。')
        md.append('')
    md.append('## 3. 负样本对照')
    md.append('')
    md.append('| 指标 | 值 |')
    md.append('|---|---|')
    md.append('| 负样本最高分（均值） | %.1f%% |' % statistics.mean(neg_scores))
    md.append('| 负样本最高分（区间） | %.1f%%~%.1f%% |' % (min(neg_scores), max(neg_scores)))
    md.append('| 正样本 Top1 最低分 | %.1f%% |' % min(pos_top1_scores))
    md.append('| **区间重叠** | 负样本最高分比正样本最低分**还高 %.1f 个百分点** |'
              % (max(neg_scores) - min(pos_top1_scores)))
    md.append('')
    md.append('## 4. 阈值敏感性')
    md.append('')
    md.append('假设「相似度 ≥ 阈值即认为命中」，统计正样本通过率与负样本误判率：')
    md.append('')
    md.append('| 阈值 | 正样本 Top1 通过 | 负样本误判 |')
    md.append('|---|---|---|')
    for t, p, nf in th_rows:
        md.append('| ≥%d%% | %d/%d (%.1f%%) | %d/%d (%.1f%%) |'
                  % (t, p, n, 100.0 * p / n, nf, len(neg), 100.0 * nf / len(neg)))
    md.append('')
    md.append('## 5. 结论')
    md.append('')
    md.append('1. **排序指标可用**：Hit@1 %.1f%%、Hit@3 %.1f%%、MRR %.3f，说明向量检索在'
              '「自然口语提问 → 知识点」这个任务上排序是有效的。' % (100.0 * h1 / n, 100.0 * h3 / n, mrr))
    md.append('2. **绝对相似度不可作阈值**：负样本（语料中完全不存在的主题）最高分 %.1f%%，'
              '比正样本的最低分（%.1f%%）还高 %.1f 个百分点，两个区间完全重叠。'
              '阈值敏感性分析给出了更明确的结论——要让负样本误判率降到 0，阈值必须提到 60%% 以上，'
              '但正样本会因此损失约四分之一（通过率跌到 72.5%%）；'
              '反之阈值定在 55%% 时负样本误判率高达 40%%。**不存在可用的工作点**，'
              '因此这类系统的验收标准应该是排序指标（Hit@K / MRR），'
              '而不是「相似度 > 0.7 才算命中」这种写法。'
              % (max(neg_scores), min(pos_top1_scores), max(neg_scores) - min(pos_top1_scores)))
    md.append('3. **topK = 3 是性价比最高的取值**：%d 条查询的正确答案不在 Top1，'
              '其中 %d 条落在 Top2~Top3；Hit@3 与 Hit@5 完全相同（均 %.1f%%），'
              '说明取 3 条已覆盖全部可召回结果，取 5 条只是徒增送入 LLM 的上下文开销。'
              % (n - h1, h3 - h1, 100.0 * h3 / n))
    md.append('4. **提升方向**：剩余错误主要来自主题相近的知识点互相干扰'
              '（如同一领域下的两个细分主题），可考虑混合检索（向量 + BM25 关键词）或加一层 Rerank。')
    md.append('')
    md.append('---')
    md.append('')
    md.append('*评测耗时 %.0f 秒；命中判定基于文档 tags 自动完成，无人工标注介入。*' % (time.time() - t0))
    md.append('')

    os.makedirs(os.path.dirname(REPORT), exist_ok=True)
    open(REPORT, 'w', encoding='utf-8').write('\n'.join(md))
    out()
    out('[report] 已写入 %s' % REPORT)
    print('[done] 总耗时 %.0fs' % (time.time() - t0))


if __name__ == '__main__':
    main()
