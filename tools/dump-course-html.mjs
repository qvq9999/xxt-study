#!/usr/bin/env node
/** 读课程页 HTML，把所有和 work / 作业 相关的 URL 片段扒出来 */
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
    setTimeout(() => { if (waiting.has(mid)) { waiting.delete(mid); rej(new Error(`${method} timeout`)); } }, 25000);
  });
};
const safe = async (fn, fb = null) => { try { return await fn(); } catch (e) { console.log('  [warn]', e.message); return fb; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const raw = async (expr) => (await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true }), null))?.result?.value;

await send('Page.enable');
await send('Runtime.enable');

const cur = String(await raw('location.href') || '');
if (!/mycourse\/stu/.test(cur)) {
  await safe(() => send('Page.navigate', {
    url: 'https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=245030810&clazzid=152663533&cpi=433049509&ismooc2=1&v=2',
  }));
  await sleep(6000);
}
await sleep(1500);

console.log('当前:', String(await raw('location.href')).slice(0, 120));

const html = String(await raw('document.documentElement.outerHTML') || '');
console.log('页面 HTML 长度:', html.length);

const seen = new Set();
const push = (s) => { const k = s.trim(); if (k && !seen.has(k)) { seen.add(k); console.log('  ', k.slice(0, 200)); } };

console.log('\n=== 含 work 的 URL / 调用片段 ===');
for (const m of html.matchAll(/[^\s"'<>()]{0,110}work[^\s"'<>()]{0,110}/gi)) {
  const s = m[0];
  if (/chaoxing\.com|\/mooc|\/work|goTask|onclick/i.test(s)) push(s);
  if (seen.size > 25) break;
}

console.log('\n=== onclick 里带 work 的处理函数 ===');
for (const m of html.matchAll(/onclick\s*=\s*"([^"]{0,160})"/gi)) {
  if (/work|作业/i.test(m[1])) push('onclick=' + m[1]);
  if (seen.size > 40) break;
}

console.log('\n=== 作业标签附近的 DOM 片段 ===');
const idx = html.indexOf('作业');
if (idx > 0) {
  const seg = html.slice(Math.max(0, idx - 700), idx + 300).replace(/\s+/g, ' ');
  console.log(seg.slice(0, 900));
} else {
  console.log('  页面 HTML 里没找到"作业"两字（可能标签是 JS 渲染的）');
}

ws.close();
