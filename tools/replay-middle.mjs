#!/usr/bin/env node
/**
 * 抓浏览器对 stucoursemiddle 的真实请求头，然后用 fetch 原样重放。
 * 目的：定位"浏览器能通、脚本不通"到底差在哪个头。
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const waiting = new Map();
const captured = [];

await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) {
    const { resolve, reject } = waiting.get(m.id);
    waiting.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
    return;
  }
  if (m.method === 'Network.requestWillBeSent' && /stucoursemiddle/.test(m.params.request.url)) {
    captured.push({ url: m.params.request.url, headers: m.params.request.headers, type: m.params.type });
  }
  if (m.method === 'Network.responseReceived' && /stucoursemiddle/.test(m.params.response.url)) {
    captured.push({ resp: m.params.response.status, respUrl: m.params.response.url, from: 'responseReceived' });
  }
});
const send = (method, params = {}) => {
  const mid = ++id;
  return new Promise((res, rej) => {
    waiting.set(mid, { resolve: res, reject: rej });
    ws.send(JSON.stringify({ id: mid, method, params }));
    setTimeout(() => waiting.has(mid) && (waiting.delete(mid), rej(new Error('timeout'))), 25000);
  });
};

await send('Network.enable');
await send('Page.enable');

const course = { courseId: '245030810', classId: '152663533', cpi: '433049509' };
const url = `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`;

await send('Page.navigate', { url });
await new Promise((r) => setTimeout(r, 4000));

const req = captured.find((c) => c.headers);
if (!req) {
  console.log('没抓到请求头');
  process.exit(1);
}

console.log('=== 浏览器实际发出的头 ===');
for (const [k, v] of Object.entries(req.headers)) {
  console.log(`  ${k}: ${String(v).slice(0, 110)}`);
}

const hasCookie = Object.keys(req.headers).some((k) => k.toLowerCase() === 'cookie');
console.log(`\nCDP 是否给了 Cookie 头: ${hasCookie ? '是' : '否（浏览器默认不带，下面自己补）'}`);

// 取当前 Cookie，按域匹配组装
const { cookies: all } = await send('Network.getAllCookies');
const cx = all.filter((c) => c.domain.includes('chaoxing'));
const host = 'mooc1.chaoxing.com';
const cookieHeader = cx
  .filter((c) => {
    const d = c.domain.startsWith('.') ? c.domain.slice(1) : c.domain;
    return c.domain.startsWith('.') ? host === d || host.endsWith('.' + d) : host === d;
  })
  .map((c) => `${c.name}=${c.value}`)
  .join('; ');

console.log('\n=== 用浏览器同款头 + 自己的 Cookie 重放 ===');
const replayHeaders = { ...req.headers };
delete replayHeaders['Cookie'];
delete replayHeaders['cookie'];
replayHeaders['Cookie'] = cookieHeader;

const r = await fetch(url, { headers: replayHeaders, redirect: 'manual', signal: AbortSignal.timeout(20000) });
const body = await r.text();
const title = (body.match(/<title>([\s\S]*?)<\/title>/) || [null, ''])[1].trim().slice(0, 40);
console.log(`  http=${r.status}  location=${r.headers.get('location') || '无'}`);
console.log(`  title="${title}"  登录页=${/用户登录/.test(body) ? '是' : '否'}`);

ws.close();
