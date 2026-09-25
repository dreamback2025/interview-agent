// 用本机 Chrome（puppeteer-core）+ headless 截取页面，输出到 docs/screenshots/
// 用法：BASE=http://127.0.0.1:8089 node scripts/ui-shot.js
const path = require('path');
const fs = require('fs');
const puppeteer = require('puppeteer-core');

const BASE = process.env.BASE || 'http://127.0.0.1:8089';
const OUT = process.env.OUT || path.join(__dirname, '..', 'docs', 'screenshots');
const CHROME = process.env.CHROME || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

(async () => {
  fs.mkdirSync(OUT, { recursive: true });
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: true,
    args: ['--no-sandbox', '--disable-gpu', '--hide-scrollbars'],
  });
  const page = await browser.newPage();
  await page.setViewport({ width: 1440, height: 900, deviceScaleFactor: 2 });

  await page.goto(BASE, { waitUntil: 'networkidle2', timeout: 60000 });

  // 0. 未登录：先截登录页（鉴权开启时的第一印象）
  await page.waitForSelector('#loginOverlay', { timeout: 20000 });
  await sleep(600);
  await page.screenshot({ path: path.join(OUT, '00-login.png') });
  console.log('[0/4] 登录页（JWT 鉴权）');

  // 登录演示账号，再截业务界面
  await page.type('#loginUser', process.env.SMOKE_USER || 'demo');
  await page.type('#loginPass', process.env.SMOKE_PASS || 'demo123');
  await page.click('#loginBtn');

  await page.waitForSelector('#records .record', { timeout: 30000 });
  await sleep(800);
  await page.screenshot({ path: path.join(OUT, '01-overview.png') });
  console.log('[1/4] 首屏（录入表单 + 历史记录）');

  await page.click('#records .record');
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '02-detail-report.png') });
  console.log('[2/4] 详情 + 复盘报告');

  await page.evaluate(() => {
    const el = document.getElementById('kbDocList') || document.getElementById('kbUpload');
    if (el) el.scrollIntoView({ block: 'center' });
  });
  await sleep(700);
  await page.screenshot({ path: path.join(OUT, '03-knowledge-base.png') });
  console.log('[3/4] 知识库（含删除按钮）');
  console.log('[4/4] 完成');

  await browser.close();
  console.log('done ->', OUT);
})().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
