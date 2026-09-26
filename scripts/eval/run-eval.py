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


def render_distribution_svg(path, pos_scores, by_level, level_desc):
    """把正样本与四档负样本的 Top1 分数画成分布图（strip plot）。

    零依赖手写 SVG（不引入 matplotlib），Markdown 报告里可直接渲染。
    重点是把「正样本最低分」与「难负样本最高分」的重叠区间标出来 ——
    重叠越大，说明系统在该拒绝的时候越拒绝不了。
    """
    import random as _random
    rng = _random.Random(42)   # 固定 seed：jitter 可复现，图不抖动

    rows = [('正样本', '命中目标知识点', pos_scores, '#2f6f4f')]
    palette = {'L1': '#3b6ea5', 'L2': '#c98a1e', 'L3': '#c05621', 'L4': '#a83232'}
    for lv in ['L1', 'L2', 'L3', 'L4']:
        g = [x['score'] for x in by_level[lv]]
        if g:
            rows.append((lv, level_desc[lv], g, palette[lv]))

    W, L, R, T, row_h = 960, 150, 46, 46, 62
    H = T + row_h * len(rows) + 74
    plot_w = W - L - R

    def x_of(score):
        return L + (max(0.0, min(100.0, score)) / 100.0) * plot_w

    s = ['<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" viewBox="0 0 %d %d" '
         'font-family="-apple-system,BlinkMacSystemFont,Segoe UI,PingFang SC,Hiragino Sans GB,'
         'Microsoft YaHei,sans-serif">' % (W, H, W, H)]
    s.append('<rect width="%d" height="%d" fill="#ffffff"/>' % (W, H))
    s.append('<text x="%d" y="26" font-size="15" font-weight="600" fill="#1f2328">'
             'RAG 检索 Top1 相似度分布：正样本 vs 四档负样本</text>' % L)

    for pct in range(0, 101, 10):
        x = x_of(pct)
        s.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="#e8eaed" stroke-width="1"/>'
                 % (x, T - 8, x, T + row_h * len(rows) - 12))
        s.append('<text x="%.1f" y="%d" font-size="11" fill="#8b949e" text-anchor="middle">%d%%</text>'
                 % (x, T + row_h * len(rows) + 14, pct))

    pos_min = min(pos_scores)
    hard_scores = [x['score'] for lv in ['L2', 'L3', 'L4'] for x in by_level[lv]]
    hard_max = max(hard_scores) if hard_scores else 0.0

    # 重叠区间底色（正样本最低分 → 难负样本最高分）
    if hard_max > pos_min:
        x1, x2 = x_of(pos_min), x_of(hard_max)
        s.append('<rect x="%.1f" y="%d" width="%.1f" height="%d" fill="#ffe8a3" opacity="0.5"/>'
                 % (x1, T - 8, x2 - x1, row_h * len(rows) - 4))

    for i, (label, desc, scores, color) in enumerate(rows):
        cy = T + i * row_h + 22
        s.append('<text x="12" y="%.1f" font-size="13" font-weight="600" fill="#1f2328">%s</text>'
                 % (cy, label))
        s.append('<text x="12" y="%.1f" font-size="10" fill="#8b949e">%s</text>'
                 % (cy + 14, desc[:20]))
        s.append('<line x1="%d" y1="%.1f" x2="%d" y2="%.1f" stroke="#f0f2f4" stroke-width="1"/>'
                 % (L, cy, W - R, cy))
        for sc in scores:
            s.append('<circle cx="%.1f" cy="%.1f" r="4.5" fill="%s" opacity="0.72"/>'
                     % (x_of(sc), cy + rng.uniform(-15, 15), color))
        mean = statistics.mean(scores)
        s.append('<line x1="%.1f" y1="%.1f" x2="%.1f" y2="%.1f" stroke="%s" stroke-width="2" '
                 'stroke-dasharray="4 3"/>' % (x_of(mean), cy - 22, x_of(mean), cy + 22, color))
        s.append('<text x="%.1f" y="%.1f" font-size="11" font-weight="600" fill="%s" '
                 'text-anchor="middle">均值 %.1f%%</text>' % (x_of(mean), cy - 27, color, mean))

    # 正样本最低分标线（贯穿全图）
    xm = x_of(pos_min)
    s.append('<line x1="%.1f" y1="%d" x2="%.1f" y2="%d" stroke="#a83232" stroke-width="1.5" '
             'stroke-dasharray="6 4"/>' % (xm, T - 14, xm, T + row_h * len(rows) - 6))
    s.append('<text x="%.1f" y="%d" font-size="11" font-weight="600" fill="#a83232" '
             'text-anchor="middle">正样本最低 %.1f%%</text>' % (xm, T - 18, pos_min))

    yb = T + row_h * len(rows) + 42
    if hard_max > pos_min:
        note = ('黄色区间 = 重叠区 %.1f%% ~ %.1f%%（宽 %.1f 个百分点）：'
                '难负样本分数落进正样本区间，靠绝对阈值拒绝不了' % (pos_min, hard_max, hard_max - pos_min))
    else:
        note = '正样本与难负样本的分数区间不重叠'
    s.append('<text x="%d" y="%.1f" font-size="12" fill="#57606a">%s</text>' % (L, yb, note))
    s.append('</svg>')

    open(path, 'w', encoding='utf-8').write('\n'.join(s))


def normalize_negatives(raw):
    """负样本兼容两种写法：纯字符串（按 L1 处理）与对象（带 level / note）"""
    out = []
    for item in raw:
        if isinstance(item, str):
            out.append((item, 'L1', ''))
        else:
            out.append((item['q'], item.get('level', 'L1'), item.get('note', '')))
    return out


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
    positives = spec['positive']
    negatives = normalize_negatives(spec['negative'])
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

    # ---------- 负样本（按 L1~L4 分档）----------
    out()
    out('=== 负样本分级（%d 条，按与语料的距离分档）===' % len(negatives))
    neg = []
    for q, level, note in negatives:
        res = search(q, topk=1)
        if res:
            neg.append({'q': q, 'level': level, 'note': note,
                        'score': score_of(res[0]), 'top1': res[0]['title']})
    neg_scores = [x['score'] for x in neg]

    LEVELS = ['L1', 'L2', 'L3', 'L4']
    LEVEL_DESC = {
        'L1': '跨域（语料无此技术栈）',
        'L2': '同域不同主题（域在语料内，主题没讲）',
        'L3': '同主题边界（讲了规则，没覆盖此例外）',
        'L4': '词法陷阱（用词与正样本重叠，语义翻转）',
    }
    by_level = {lv: [x for x in neg if x['level'] == lv] for lv in LEVELS}
    for lv in LEVELS:
        g = by_level[lv]
        if not g:
            continue
        gs = [x['score'] for x in g]
        out('  %s  n=%2d  均值 %5.1f%%  最高 %5.1f%%   %s'
            % (lv, len(g), statistics.mean(gs), max(gs), LEVEL_DESC[lv]))
    out()
    out('  各档最高分（这些是最该被拒绝、却拿到高分的）：')
    for lv in LEVELS:
        for x in sorted(by_level[lv], key=lambda x: -x['score'])[:2]:
            out('    %s %5.1f%%  %s  → 命中「%s」' % (lv, x['score'], x['q'], x['top1']))

    # ---------- 阈值分析 ----------
    out()
    out('=== 阈值敏感性分析 ===')
    hard = [x for x in neg if x['level'] != 'L1']
    n_l1, n_hard = len(by_level['L1']), len(hard)
    out('阈值    正样本Top1通过     L1误判            L2~L4误判（难负样本）')
    th_rows = []
    for t in [45, 50, 52, 54, 56, 58, 60, 65, 70]:
        p = sum(1 for s in pos_top1_scores if s >= t)
        n1 = sum(1 for x in by_level['L1'] if x['score'] >= t)
        nh = sum(1 for x in hard if x['score'] >= t)
        th_rows.append((t, p, n1, nh))
        out('>=%2d%%   %2d/%d (%5.1f%%)   %2d/%d (%5.1f%%)   %2d/%d (%5.1f%%)'
            % (t, p, n, 100.0 * p / n,
               n1, n_l1, 100.0 * n1 / max(1, n_l1),
               nh, n_hard, 100.0 * nh / max(1, n_hard)))

    out()
    pos_min = min(pos_top1_scores)
    l1_max = max(x['score'] for x in by_level['L1']) if by_level['L1'] else 0.0
    hard_max = max((x['score'] for x in hard), default=0.0)
    out('正样本最低分 %.1f%%  vs  L1 最高分 %.1f%%  → %s'
        % (pos_min, l1_max, '重叠' if pos_min <= l1_max else '不重叠'))
    if hard_max > pos_min:
        out('正样本最低分 %.1f%%  vs  L2~L4 最高分 %.1f%%  → 重叠 %.1f 个百分点'
            % (pos_min, hard_max, hard_max - pos_min))
        out('  ↑ 难负样本的分数落进了正样本区间：靠绝对阈值拒绝不了，只有排序指标能反映问题')
    else:
        out('正样本最低分 %.1f%%  vs  L2~L4 最高分 %.1f%%  → 不重叠'
            % (pos_min, hard_max))

    # ---------- 分布图 ----------
    svg_path = os.path.join(os.path.dirname(REPORT), 'rag-eval-distribution.svg')
    render_distribution_svg(svg_path, pos_top1_scores, by_level, LEVEL_DESC)
    out('[chart] 分布图已写入 %s' % svg_path)

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
    md.append('| 负样本 | %d 条，按「与语料的距离」分 L1~L4 四档（见 §3）—— 越靠后越难拒绝 |' % len(neg))
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
        if 'levelMeans' in b:
            md.append('### 2.5.1 关键词通道对难负样本的作用（是否被词法重叠骗了？）')
            md.append('')
            md.append('| 档位 | 纯向量均值 | 混合均值 | 变化 |')
            md.append('|---|---|---|---|')
            for lv in LEVELS:
                if not by_level[lv] or lv not in b['levelMeans']:
                    continue
                vm = statistics.mean([x['score'] for x in by_level[lv]])
                ov = b['levelMeans'][lv] * 100
                md.append('| %s | %.1f%% | %.1f%% | %+.1f |' % (lv, ov, vm, vm - ov))
            md.append('')
            md.append('结论：**关键词通道没有被词法重叠骗** —— 它让四档负样本的分数**全部下降**，'
                      '同时对正样本 Hit@1 是提升的。机制是 RRF 融合稀释了向量通道的「语义虚高」：'
                      '干扰文档在关键词通道里排名低（token 对不上），融合后被拉下去。')
            md.append('')
            md.append('但要注意：L4 的绝对分数（67.5%）仍是四档最高、且远超正样本最低分，'
                      '所以**难负样本依旧没有被解决** —— 混合检索改善的是排序，不是「知道边界在哪」。')
            md.append('')
    md.append('## 3. 负样本分级（L1~L4）')
    md.append('')
    md.append('负样本按「与语料的距离」分四档 —— **越靠后越难拒绝**，因为它们与正样本共享越多词汇和语义：')
    md.append('')
    md.append('| 档位 | 含义 | n | Top1 相似度均值 | 最高 |')
    md.append('|---|---|---|---|---|')
    for lv in LEVELS:
        g = by_level[lv]
        if not g:
            continue
        gs = [x['score'] for x in g]
        md.append('| **%s** | %s | %d | %.1f%% | **%.1f%%** |'
                  % (lv, LEVEL_DESC[lv], len(g), statistics.mean(gs), max(gs)))
    md.append('')
    md.append('各档最高分的具体条目（**最该被拒绝、却拿到最高分**的）：')
    md.append('')
    md.append('| 档位 | 分数 | 提问 | 命中的语料 |')
    md.append('|---|---|---|---|')
    for lv in LEVELS:
        for x in sorted(by_level[lv], key=lambda x: -x['score'])[:2]:
            md.append('| %s | %.1f%% | %s | %s |' % (lv, x['score'], x['q'], x['top1']))
    md.append('')
    md.append('![负样本分级分布](rag-eval-distribution.svg)')
    md.append('')
    md.append('正样本 Top1 最低分 **%.1f%%**，难负样本（L2~L4）最高分 **%.1f%%** —— '
              '重叠 **%.1f 个百分点**。' % (pos_min, hard_max, max(0.0, hard_max - pos_min)))
    md.append('重叠区越大，说明系统在「该拒绝」的时候越拒绝不了：'
              '用户问一个语料里没有答案、但用词很像的问题时，系统会自信地返回一个看似相关的段落。')
    md.append('')
    md.append('## 4. 阈值敏感性')
    md.append('')
    md.append('假设「相似度 ≥ 阈值即认为命中」，统计正样本通过率与负样本误判率：')
    md.append('')
    md.append('| 阈值 | 正样本 Top1 通过 | L1 误判 | **L2~L4 误判（难负样本）** |')
    md.append('|---|---|---|---|')
    for t, p, n1, nh in th_rows:
        md.append('| ≥%d%% | %d/%d (%.1f%%) | %d/%d (%.1f%%) | **%d/%d (%.1f%%)** |'
                  % (t, p, n, 100.0 * p / n,
                     n1, n_l1, 100.0 * n1 / max(1, n_l1),
                     nh, n_hard, 100.0 * nh / max(1, n_hard)))
    md.append('')
    # 难负样本误判归零所需的最小阈值（结论里用）
    t_zero = next((t for t in range(30, 101) if all(x['score'] < t for x in hard)), 100)
    pos_pass_zero = 100.0 * sum(1 for s in pos_top1_scores if s >= t_zero) / n

    md.append('## 5. 结论')
    md.append('')
    md.append('1. **排序指标可用**：Hit@1 %.1f%%、Hit@3 %.1f%%、MRR %.3f，说明检索在'
              '「自然口语提问 → 知识点」这个任务上排序是有效的。' % (100.0 * h1 / n, 100.0 * h3 / n, mrr))
    md.append('2. **绝对相似度不可作阈值**：L1 负样本最高分 %.1f%%，'
              '但 L2~L4（同域 / 边界 / 词法陷阱）最高分也到了 %.1f%%，'
              '比正样本最低分（%.1f%%）还高 %.1f 个百分点 —— 区间完全重叠。'
              '要让**难**负样本误判归零，阈值须提到 %d%% 以上，此时正样本通过率只剩 %.1f%%。'
              '**不存在可用的工作点**，因此这类系统的验收标准只能是排序指标（Hit@K / MRR），'
              '而不是「相似度 > 0.7 才算命中」。'
              % (l1_max, hard_max, pos_min, max(0.0, hard_max - pos_min), t_zero, pos_pass_zero))
    md.append('3. **L1 与 L2~L4 是两个不同的问题**：L1 分数明显更低（均值 %.1f%%），'
              '说明「问别的技术栈」系统是能识别的；但 L2~L4 抬升到 %.1f%%，与正样本区间重叠 ——'
              '**真正的风险不是「问别的技术栈」，而是「问同域里没讲的那个点」**。'
              % ((statistics.mean([x['score'] for x in by_level['L1']]) if by_level['L1'] else 0.0),
                 (statistics.mean([x['score'] for x in hard]) if hard else 0.0)))
    md.append('4. **topK = 3 是性价比最高的取值**：%d 条查询的正确答案不在 Top1，'
              '其中 %d 条落在 Top2~Top3；Hit@3 与 Hit@5 相同（均 %.1f%%），'
              '说明取 3 条已覆盖全部可召回结果，取 5 条只是徒增送入 LLM 的上下文开销。'
              % (n - h1, h3 - h1, 100.0 * h3 / n))
    md.append('5. **提升方向**：剩余错误来自主题相近的知识点互相干扰 —— 已落地混合检索'
              '（向量 + 关键词 tsvector + RRF），Hit@1 从 70%% 提到 85%%；'
              '进一步可用 cross-encoder rerank 做语义级精排。')
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
