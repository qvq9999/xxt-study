#!/usr/bin/env node
/**
 * 点 a[title="作业"]，抓浏览器实际发出的作业列表请求（含全部参数），
 * 再单独打开该 URL 验证作业行能解析出来。
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';
const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const waiting = new Map();
const reqs = [];

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
    if (/work|homework/i.test(u)) reqs.push({ url: u, type: m.params.type, method: m.params.request.method });
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
const safe = async (fn, fb = null) => { try { return await fn(); } catch (e) { console.log('  [warn]', e.message); return fb; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const raw = async (expr) => (await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true }), null))?.result?.value;

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');

const cur = String(await raw('location.href') || '');
if (!/mycourse\/stu/.test(cur)) {
  await safe(() => send('Page.navigate', {
    url: 'https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=245030810&clazzid=152663533&cpi=433049509&ismooc2=1&v=2',
  }));
  await sleep(6000);
}

console.log('=== 点 a[title=作业] ===');
const clicked = await raw(`
(function(){
  var a = document.querySelector('a[title="作业"]');
  if (!a) return 'not-found';
  var li = a.closest('li');
  a.click();
  return 'clicked, li attrs: ' + (li ? li.getAttribute('datatype') + ' data=' + li.getAttribute('data') + ' pageheader=' + li.getAttribute('pageheader') : '无 li');
})()
`);
console.log(' ', clicked);
await sleep(6000);

console.log('\n=== 捕获到的 work 相关请求 ===');
if (!reqs.length) console.log('  没捕获到（可能作业在一开始就预加载了）');
reqs.slice(0, 8).forEach((r, i) => {
  console.log(`  [${i}] ${r.type} ${r.method}`);
  console.log(`      ${r.url.slice(0, 300)}`);
});

const workReq = reqs.find((r) => r.url.includes('/mooc2/work/list'));
if (workReq) {
  console.log('\n=== 单独打开捕获的 URL ===');
  await safe(() => send('Page.navigate', { url: workReq.url }));
  await sleep(5000);
  const d = JSON.parse(await (async () => {
    const v = await raw(`
    (function(){
      var lis = Array.from(document.querySelectorAll('li[data]')).filter(function(li){
        return (li.getAttribute('data')||'').indexOf('chaoxing.com') >= 0;
      });
      return JSON.stringify({
        href: location.href, title: document.title, rows: lis.length,
        samples: lis.slice(0,6).map(function(li){
          return (li.getAttribute('aria-label')||'') + ' => ' + (li.getAttribute('data')||'').slice(0,100);
        }),
        text: (document.body?document.body.innerText:'').slice(0,150).replace(/\\s+/g,' ')
      });
    })()
    `);
    try { return v || '{}'; } catch { return '{}'; }
  })());
  console.log('  title=', d.title, ' rows=', d.rows);
  console.log('  text=', d.text);
  (d.samples || []).forEach((s) => console.log('   -', s));
  if (d.rows > 0) {
    console.log('\n>>> 作业列表拿到了。复现用的完整 URL：');
    console.log(workReq.url);
  }
} else {
  const info = JSON.parse(await (async () => {
    const v = await raw(`JSON.stringify({frames: Array.from(document.querySelectorAll('iframe')).map(function(f){return f.src;}), href: location.href})`);
    try { return v || '{}'; } catch { return '{}'; }
  })());
  console.log('\n  当前 iframe:', (info.frames || []).map((f) => String(f).slice(0, 200)));
}

ws.close();
