// 用 jsdom 真实执行页面 JS，验证：初始化 -> 录入 -> 列表 -> 详情 -> 分析 -> 报告渲染
const fs = require('fs');
const { JSDOM, VirtualConsole } = require('jsdom');

const BASE = process.env.BASE || 'http://127.0.0.1:8081';
// SKIP_SLOW=1 只跑快检查（表单/历史/导出/知识库），跳过 Agent 与模拟面试（各要几十秒）
const SKIP_SLOW = process.env.SKIP_SLOW === '1';
const html = fs.readFileSync(__dirname + '/../src/main/resources/static/index.html', 'utf-8');

const errors = [];
const vc = new VirtualConsole();
vc.on('jsdomError', e => errors.push('jsdomError: ' + e.message));
vc.on('error', (...a) => errors.push('console.error: ' + a.join(' ')));

const dom = new JSDOM(html, {
  runScripts: 'dangerously',
  url: BASE + '/',
  virtualConsole: vc,
  beforeParse(window) {
    window.fetch = (u, o) => globalThis.fetch(new URL(u, BASE).href, o);
  }
});
const win = dom.window;
const doc = win.document;
const $ = id => doc.getElementById(id);
const sleep = ms => new Promise(r => setTimeout(r, ms));

(async () => {
  await sleep(1200);

  console.log('1) 模式徽章      :', $('mode').textContent);
  console.log('1.5) 当前库信息  :', ($('dbInfo') ? $('dbInfo').textContent : '(未渲染)'));
  console.log('2) 初始题目行数  :', doc.querySelectorAll('#questions .q-item').length, '(应为 1)');

  // 填表单
  $('company').value = 'UI 测试科技';
  $('position').value = 'Java 后端';
  $('interviewDate').value = '2026-09-22';
  $('jd').value = 'Java 并发、JVM、MySQL 索引';
  const f = doc.querySelectorAll('#questions .q-item textarea, #questions .q-item input');
  f[0].value = '讲一下 G1 回收流程';
  f[1].value = '说得比较含糊';
  f[2].value = '面试官追问了 Mixed GC';
  f[3].value = '4';
  f[4].value = 'JVM,GC';

  $('submit').click();
  await sleep(1500);

  console.log('3) 保存后记录数  :', doc.querySelectorAll('#records .record').length, '(应 >= 1)');
  console.log('4) 详情标题      :', (doc.querySelector('#detail b') || {}).textContent || '(空)');
  console.log('5) 表单错误提示  :', $('formErr').textContent || '(无)');

  const btn = $('analyzeBtn');
  if (!btn) { console.log('6) 未找到分析按钮'); }
  else {
    btn.click();
    await sleep(30000);   // 真实模型通常十几秒，留足时间
    const report = $('report');
    const txt = report ? report.textContent : '';
    console.log('6) 报告已渲染    :', txt.includes('复盘报告') ? '是' : '否');
    console.log('7) 报告含盲区标签:', doc.querySelectorAll('#report .tag.gap').length, '个');
    console.log('8) 报告含优先点  :', doc.querySelectorAll('#report .focus').length, '个');
    console.log('9) 详情含弱项回写:', doc.querySelector('#detail .qa.weak') ? '是' : '否');

    const tags = doc.querySelectorAll('#history .tag[data-aid]');
    console.log('10) 历史分析条数 :', tags.length, '(应 >= 1)');
    if (tags.length) {
      console.log('11) 历史标签文本 :', tags[0].textContent);
      tags[tags.length - 1].click();          // 点最旧的一条，验证回看
      await sleep(1200);
      const t = $('report').textContent;
      console.log('12) 回看报告渲染 :', t.includes('复盘报告') ? '是' : '否');
    console.log('13) 选中态高亮   :', doc.querySelectorAll('#history .tag.active').length, '个');
  }

  $('exportBtn').click();
  await sleep(1000);
  console.log('14) 导出按钮提示 :', $('toast').textContent || '(无提示)');

  // 知识库区块（本环境无 Postgres/Ollama，应优雅报错而不是崩）
  $('kbSample').click();
  console.log('15) 示例片段填入 :', $('kbContent').value.length > 50 ? '是' : '否');
  $('kbUpload').click();
  await sleep(2000);
  console.log('16) 入库结果     :', (($('kbErr').textContent || $('toast').textContent) || '(无)').slice(0, 70));
  $('kbQuery').value = 'G1 回收流程';
  $('kbSearch').click();
  await sleep(2000);
  console.log('17) 检索结果     :', ($('kbResults').textContent || '(空)').slice(0, 70));

  // 流式复盘（SSE）
  const sb = $('streamBtn');
  if (sb) {
    sb.click();
    await sleep(30000);
    const out = $('streamOut');
    const t = out ? out.textContent : '';
    console.log('24) 流式复盘输出 :', t.length + ' 字, 含「复盘报告」:', t.includes('复盘报告') ? '是' : '否');
  } else {
    console.log('24) 未找到流式按钮');
  }

  // 模拟面试：出 1 题 -> 简短作答（预期被追问）-> 结束看总评
  if (SKIP_SLOW) {
    console.log('20~23) 模拟面试  : SKIP_SLOW=1，已跳过');
  } else {
  $('mkJd').value = '招聘 Java 后端：Java 并发、JVM、MySQL 索引、Redis 缓存';
  $('mkCount').value = '1';
  $('mkStart').click();
  await sleep(40000);
  console.log('20) 模拟面试状态 :', $('mkStatus').textContent || '(无)');
  const ans = $('mkAnswer');
  if (ans) {
    // 第 1 轮故意答得浅 -> 预期被追问；最多答 3 轮，直至本题结束
    let rounds = 0;
    let ended = false;
    ans.value = '大概就是用 synchronized 保证线程安全吧，具体不太记得了';
    while (rounds < 3 && !ended) {
      rounds++;
      $('mkSubmit').click();
      await sleep(40000);
      const panel = $('mkPanel').textContent;
      if (rounds === 1) {
        console.log('21) 出现得分/追问 :', /本轮得分|面试官追问/.test(panel) ? '是' : '否');
      }
      if ($('mkNext') || $('mkFinish')) { ended = true; break; }
      const ta = $('mkAnswer');
      if (!ta) break;
      ta.value = 'ReentrantLock 相比 synchronized 多了可中断获取锁 lockInterruptibly、'
        + '超时获取 tryLock(timeout)、公平锁、多个 Condition 条件队列，且能查询锁状态；'
        + 'synchronized 由 JVM 保证自动释放，不能中断也不能超时。';
    }
    console.log('22) 共作答轮数   :', rounds, '| 本题已结束:', ended ? '是' : '否');
    const next = $('mkNext') || $('mkFinish');
    if (next) {
      next.click();
      await sleep(40000);
      console.log('23) 总评渲染     :', /总分/.test($('mkPanel').textContent) ? '是' : '否');
    } else {
      console.log('23) 总评渲染     : 未到结束步骤');
    }
  } else {
    console.log('21) 未渲染作答框');
  }

  // Agent 模式（模型自主调用工具）—— 很慢，放最后
  const ab = $('agentBtn');
  if (ab) {
    ab.click();
    await sleep(45000);
    const t = $('report').textContent;
    console.log('18) Agent 报告   :', t.includes('复盘报告') ? '是' : '否');
    console.log('19) Agent 提示   :', $('toast').textContent || '(无)');
  }
  }
  }

  console.log('\n运行时错误:', errors.length ? errors : '无');
  win.close();
  process.exit(errors.length ? 1 : 0);
})();
