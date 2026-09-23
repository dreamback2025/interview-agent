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
  await page.waitForSelector('#records .record', { timeout: 20000 });
  await sleep(800);
  await page.screenshot({ path: path.join(OUT, '01-overview.png') });
  console.log('[1/3] 首屏（录入表单 + 历史记录）');

  await page.click('#records .record');
  await sleep(3000);
  await page.screenshot({ path: path.join(OUT, '02-detail-report.png') });
  console.log('[2/3] 详情 + 复盘报告');

  await page.evaluate(() => {
    const el = document.getElementById('kbDocList') || document.getElementById('kbUpload');
    if (el) el.scrollIntoView({ block: 'center' });
  });
  await sleep(700);
  await page.screenshot({ path: path.join(OUT, '03-knowledge-base.png') });
  console.log('[3/3] 知识库（含删除按钮）');

  await browser.close();
  console.log('done ->', OUT);
})().catch((e) => {
  console.error('FAILED:', e.message);
  process.exit(1);
});
