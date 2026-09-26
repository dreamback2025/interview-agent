import json, urllib.request, urllib.parse, os

B = 'http://127.0.0.1:8092'
op = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def api(p, data=None, tok=None):
    req = urllib.request.Request(B + p, method='POST' if data else 'GET')
    body = None
    if data:
        body = json.dumps(data).encode()
        req.add_header('Content-Type', 'application/json')
    if tok:
        req.add_header('Authorization', 'Bearer ' + tok)
    with op.open(req, body, timeout=60) as r:
        t = r.read().decode()
        return json.loads(t) if t.strip() else None


tok = api('/api/auth/login', {'username': 'demo', 'password': 'demo123'})['token']
spec = json.load(open('scripts/eval/queries.json', encoding='utf-8'))
pos = spec['positive']
neg = spec['negative']
LEVELS = ['L1', 'L2', 'L3', 'L4']


def ask(q):
    return api('/api/knowledge/ask?' + urllib.parse.urlencode({'q': q, 'topK': 5}), tok=tok)


print('=' * 78)
print('正样本被拒（漏答）—— 用户问得到答案，却被拒了')
print('=' * 78)
miss = 0
for it in pos:
    r = ask(it['q'])
    if not r.get('confident', True):
        s = r.get('signals', {})
        miss += 1
        print('  %d) [%s] %s' % (miss, it['expect'], it['q']))
        print('     vec=%.2f  cov=%.2f  | %s' % (s.get('vecTop1', 0), s.get('kwCoverage', 0),
                                                 (r.get('reason') or '')[:44]))
print('  合计漏答: %d/%d = %.1f%%' % (miss, len(pos), 100.0 * miss / len(pos)))

print()
print('=' * 78)
print('负样本被放行（误答）—— 语料里没有，系统却给了答案')
print('=' * 78)
by_lv = {}
for it in neg:
    r = ask(it['q'])
    if r.get('confident', True):
        s = r.get('signals', {})
        top = (r.get('results') or [{'title': '(空)'}])[0]
        by_lv.setdefault(it['level'], []).append((it['q'], s.get('vecTop1', 0), s.get('kwCoverage', 0), top.get('title')))
tot = 0
for lv in LEVELS:
    g = by_lv.get(lv, [])
    n_lv = sum(1 for i in neg if i['level'] == lv)
    tot += len(g)
    print('\n  %s 被放行 %d/%d:' % (lv, len(g), n_lv))
    for q, v, c, title in sorted(g, key=lambda x: -x[1]):
        print('    vec=%.2f cov=%.2f  %s' % (v, c, q))
        print('                      → 硬答「%s」' % title)
print('\n  合计误答: %d/%d = %.1f%%' % (tot, len(neg), 100.0 * tot / len(neg)))
print()
print('总体: 正确决策 %d/%d = %.1f%%' % (len(pos) - miss + len(neg) - tot, len(pos) + len(neg),
                                   100.0 * (len(pos) - miss + len(neg) - tot) / (len(pos) + len(neg))))
