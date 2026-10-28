#!/usr/bin/env node
/**
 * 用课程页「原配」的 enc + t 去请求作业列表，和"用新时间戳"对照。
 * 假设：enc 与 t 成对校验，换 t 就会「无权限的操作」。
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';
const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const waiting = new Map();
await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) {
    const { resolve, reject } = waiting.get(m.id);
    waiting.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
  }
});
const send = (method, params = {}) => {
  const mid = ++id;
  return new Promise((res, rej) => {
    waiting.set(mid, { resolve: res, reject: rej });
    ws.send(JSON.stringify({ id: mid, method, params }));
    setTimeout(() => { if (waiting.has(mid)) { waiting.delete(mid); rej(new Error(`${method} timeout`)); } }, 20000);
  });
};
const safe = async (fn, fb = null) => { try { return await fn(); } catch (e) { console.log('  [warn]', e.message); return fb; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const raw = async (expr) => (await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true }), null))?.result?.value;

await send('Page.enable');
await send('Runtime.enable');

// 保证当前在课程页
const cur = String(await raw('location.href') || '');
if (!/mycourse\/stu/.test(cur)) {
  console.log('当前不在课程页，先打开...');
  await safe(() => send('Page.navigate', {
    url: 'https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=245030810&clazzid=152663533&cpi=433049509&ismooc2=1&v=2',
  }));
  await sleep(5500);
}

const info = JSON.parse(await (async () => {
  const v = await raw(`JSON.stringify({href: location.href, frames: Array.from(document.querySelectorAll('iframe')).map(function(f){return f.src;})})`);
  try { return v || '{}'; } catch { return '{}'; }
})());

const href = String(info.href || '');
const enc = (href.match(/[?&]enc=([0-9a-f]+)/) || [])[1] || '';
const t = (href.match(/[?&]t=(\d+)/) || [])[1] || '';
console.log('课程页:', href.slice(0, 140));
console.log('enc =', enc, '\nt   =', t);

const frame = (info.frames || []).find((f) => /stuActiveList|work/i.test(f || '')) || '';
const fstuenc = (frame.match(/[?&]stuenc=([0-9a-f]+)/) || [])[1] || '';
const ft = (frame.match(/[?&]t=(\d+)/) || [])[1] || '';
console.log('iframe stuenc =', fstuenc, ' t =', ft);

const C = { courseId: '245030810', classId: '152663533', cpi: '433049509' };
const build = (encV, tV, label) =>
  [`https://mooc1.chaoxing.com/mooc2/work/list?courseId=${C.courseId}&classId=${C.classId}&cpi=${C.cpi}&ut=s&t=${tV}&stuenc=${fstuenc || encV}&enc=${encV}`, label];

const trials = [
  build(enc, t, 'A 原配 enc + 原配 t'),
  build(fstuenc || enc, ft || t, 'B iframe 的 stuenc + iframe 的 t'),
  build(enc, Date.now(), 'C 原配 enc + 新 t'),
];

for (const [url, label] of trials) {
  console.log(`\n=== ${label} ===`);
  await safe(() => send('Page.navigate', { url }));
  await sleep(4500);
  const d = JSON.parse(await (async () => {
    const v = await raw(`
    (function(){
      var lis = Array.from(document.querySelectorAll('li[data]')).filter(function(li){
        return (li.getAttribute('data')||'').indexOf('chaoxing.com') >= 0;
      });
      return JSON.stringify({
        href: location.href, title: document.title, rows: lis.length,
        samples: lis.slice(0,4).map(function(li){
          return (li.getAttribute('aria-label')||'') + ' => ' + (li.getAttribute('data')||'').slice(0,80);
        }),
        text: (document.body?document.body.innerText:'').slice(0,90).replace(/\\s+/g,' ')
      });
    })()
    `);
    try { return v || '{}'; } catch { return '{}'; }
  })());
  console.log('  title=', d.title, ' rows=', d.rows);
  console.log('  text=', d.text);
  (d.samples || []).forEach((s) => console.log('   -', s));
  if (d.rows > 0) {
    console.log('\n>>> 通了。可用 URL:');
    console.log('   ', String(d.href).slice(0, 320));
    break;
  }
}
ws.close();
