#!/usr/bin/env node
/**
 * 关键一步：找出作业列表的 enc（≠ stuenc）在课程页里从哪来。
 *
 * 做法：同一次页面加载里，先存 outerHTML，再点「作业」抓真实请求，
 * 然后回 HTML 里搜那个 enc 值，打印它所在的元素/JS 上下文。
 * 顺带对多门课跑一遍，找出真正有作业的课来验证行解析。
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';
const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const waiting = new Map();
let reqs = [];

await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) {
    const { resolve, reject } = waiting.get(m.id);
    waiting.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
    return;
  }
  if (m.method === 'Network.requestWillBeSent') {
    const u = m.params.request.url;
    if (u.includes('/mooc2/work/list')) reqs.push(u);
  }
});
const send = (method, params = {}) => {
  const mid = ++id;
  return new Promise((res, rej) => {
    waiting.set(mid, { resolve: res, reject: rej });
    ws.send(JSON.stringify({ id: mid, method, params }));
    setTimeout(() => { if (waiting.has(mid)) { waiting.delete(mid); rej(new Error(`${method} timeout`)); } }, 25000);
  });
};
const safe = async (fn, fb = null) => { try { return await fn(); } catch (e) { console.log('    [warn]', e.message); return fb; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const raw = async (expr) => (await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true }), null))?.result?.value;
const asJson = async (expr) => { const v = await raw(expr); try { return JSON.parse(v || '{}'); } catch { return {}; } };

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');

const courses = [
  { courseId: '245030810', classId: '152663533', cpi: '433049509', name: '中华民族共同体概论' },
  { courseId: '265956858', classId: '151978333', cpi: '433049509' },
  { courseId: '265965087', classId: '152023513', cpi: '433049509' },
  { courseId: '265795351', classId: '151232718', cpi: '433049509' },
];

let encSourceFound = false;

for (const c of courses.slice(0, 3)) {
  console.log(`\n########## 课程 ${c.courseId} ${c.name || ''} ##########`);
  reqs = [];

  await safe(() => send('Page.navigate', {
    url: `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${c.courseId}&clazzid=${c.classId}&cpi=${c.cpi}&ismooc2=1&v=2`,
  }));
  await sleep(6000);

  const pageInfo = await asJson(`JSON.stringify({href: location.href, html: document.documentElement.outerHTML})`);
  const href = String(pageInfo.href || '');
  const html = String(pageInfo.html || '');
  const stuenc = (href.match(/[?&]enc=([0-9a-f]+)/) || [])[1] || '';
  console.log('  课程页 enc(=stuenc):', stuenc);

  // 点作业标签
  const clicked = await raw(`(function(){var a=document.querySelector('a[title="作业"]'); if(!a) return 'not-found'; a.click(); return 'ok';})()`);
  await sleep(5500);

  const workUrl = reqs[0] || '';
  console.log('  点击结果:', clicked, '| 捕获 work/list:', workUrl ? '有' : '无');

  if (workUrl) {
    const workEnc = (workUrl.match(/[?&]enc=([0-9a-f]+)/) || [])[1] || '';
    const t = (workUrl.match(/[?&]t=(\d+)/) || [])[1] || '';
    console.log('  作业 enc:', workEnc, ' t:', t);

    if (workEnc && workEnc !== stuenc && !encSourceFound) {
      // 在 HTML 里找这个 enc
      const pos = html.indexOf(workEnc);
      console.log('  在课程页 HTML 中的位置:', pos >= 0 ? pos : '未找到（可能是 JS 动态生成的）');
      if (pos >= 0) {
        const seg = html.slice(Math.max(0, pos - 260), pos + 120).replace(/\s+/g, ' ');
        console.log('  上下文:', seg.slice(0, 380));
        encSourceFound = true;
      } else {
        // 找可能的生成逻辑：搜索 work/list 附近的 JS
        const k = html.indexOf('mooc2/work/list');
        if (k >= 0) console.log('  work/list 出现处:', html.slice(Math.max(0, k - 280), k + 160).replace(/\s+/g, ' ').slice(0, 420));
      }
    }

    // 打开作业列表看行
    await safe(() => send('Page.navigate', { url: workUrl }));
    await sleep(4500);
    const d = await asJson(`
    (function(){
      var lis = Array.from(document.querySelectorAll('li[data]')).filter(function(li){
        return (li.getAttribute('data')||'').indexOf('chaoxing.com') >= 0;
      });
      return JSON.stringify({
        title: document.title, rows: lis.length,
        samples: lis.slice(0,4).map(function(li){
          return (li.getAttribute('aria-label')||'') + ' => ' + (li.getAttribute('data')||'').slice(0,90);
        }),
        text: (document.body?document.body.innerText:'').slice(0,110).replace(/\\s+/g,' ')
      });
    })()
    `);
    console.log('  作业页 title=', d.title, ' rows=', d.rows);
    console.log('  正文:', d.text);
    (d.samples || []).forEach((s) => console.log('    -', s));
    if (d.rows > 0) {
      console.log('\n  >>> 有作业的课找到了，复现 URL:');
      console.log('  ', workUrl);
    }
  }
}

ws.close();
