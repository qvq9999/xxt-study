#!/usr/bin/env node
/**
 * 打印浏览器里的 chaoxing Cookie 明细（域名/路径/同名冲突），
 * 用来确认「同名 Cookie 被平铺后串味」这个假设。
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
if (!page) {
  console.error('没有可用的 page target');
  process.exit(1);
}

const ws = new WebSocket(page.webSocketDebuggerUrl);
let id = 0;
const waiting = new Map();

await new Promise((res, rej) => {
  ws.addEventListener('open', res);
  ws.addEventListener('error', rej);
});

ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) {
    const { resolve, reject } = waiting.get(m.id);
    waiting.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
  }
});

function send(method, params = {}) {
  const mid = ++id;
  return new Promise((resolve, reject) => {
    waiting.set(mid, { resolve, reject });
    ws.send(JSON.stringify({ id: mid, method, params }));
    setTimeout(() => waiting.has(mid) && (waiting.delete(mid), reject(new Error('timeout'))), 15000);
  });
}

await send('Network.enable');
const { cookies } = await send('Network.getAllCookies');
const cx = cookies.filter((c) => c.domain.includes('chaoxing'));

console.log(`chaoxing Cookie 共 ${cx.length} 条\n`);
const byName = new Map();
for (const c of cx) {
  const row = `${c.name.padEnd(16)} domain=${c.domain.padEnd(26)} path=${(c.path || '').padEnd(6)} len=${c.value.length}`;
  console.log('  ' + row);
  byName.set(c.name, (byName.get(c.name) || 0) + 1);
}

const dup = [...byName.entries()].filter(([, n]) => n > 1);
console.log('\n同名 Cookie：', dup.length ? dup.map(([n, c]) => `${n}×${c}`).join('  ') : '无');

console.log('\n按 host 分组后各自会带哪些 Cookie：');
const hosts = [
  'i.chaoxing.com',
  'mooc1.chaoxing.com',
  'mooc1-api.chaoxing.com',
  'mooc2-ans.chaoxing.com',
  'passport2.chaoxing.com',
];
for (const h of hosts) {
  const hit = cx.filter((c) => {
    const d = c.domain.startsWith('.') ? c.domain.slice(1) : c.domain;
    return c.domain.startsWith('.') ? h === d || h.endsWith('.' + d) : h === d;
  });
  const names = hit.map((c) => c.name + (c.value ? '' : '(空)'));
  console.log(`  ${h.padEnd(26)} ${hit.length} 条: ${names.join(', ')}`);
}

console.log('\n平铺成一个头时的 JSESSIONID 个数：',
  cx.filter((c) => c.name === 'JSESSIONID').length);
ws.close();
