#!/usr/bin/env node
/**
 * 对照实验：为什么课程中间页返回登录页
 *
 * 变量一：Cookie 是否按域名匹配（浏览器行为）还是全平铺（我之前的做法）
 * 变量二：用 mooc1.chaoxing.com 还是 mooc1-1.chaoxing.com
 *
 * 依赖：Edge 已用 --remote-debugging-port 启动且已登录
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
    setTimeout(() => waiting.has(mid) && (waiting.delete(mid), rej(new Error('timeout'))), 15000);
  });
};
await send('Network.enable');
const { cookies: all } = await send('Network.getAllCookies');
ws.close();
const cx = all.filter((c) => c.domain.includes('chaoxing'));

function domainMatch(cookie, host) {
  const d = cookie.domain.startsWith('.') ? cookie.domain.slice(1) : cookie.domain;
  if (cookie.domain.startsWith('.')) return host === d || host.endsWith('.' + d);
  return host === d;
}
const headerFor = (host, mode) => {
  const pick = mode === 'flat' ? cx : cx.filter((c) => domainMatch(c, host));
  return pick.map((c) => `${c.name}=${c.value}`).join('; ');
};

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const course = { courseId: '245030810', classId: '152663533', cpi: '433049509' };

const targets = [
  ['mooc1.chaoxing.com', `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`],
  ['mooc1-1.chaoxing.com', `https://mooc1-1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`],
];

function probe(html) {
  const title = (html.match(/<title>([\s\S]*?)<\/title>/) || [null, ''])[1].trim().slice(0, 30);
  const isLogin = /用户登录|请输入密码|needVcode/.test(html) && !/workEnc|作业/.test(html);
  const inputs = [...html.matchAll(/<input\b[^>]*\bid="(enc|openc|oldenc|workEnc|examEnc)"[^>]*>/gi)]
    .map((m) => m[1]);
  return { title, isLogin, keys: [...new Set(inputs)].join(',') || '无' };
}

for (const [host, url] of targets) {
  for (const mode of ['match', 'flat']) {
    const hdr = headerFor(host, mode);
    const n = hdr.split('; ').length;
    let r;
    try {
      r = await fetch(url, {
        headers: { 'User-Agent': UA, Cookie: hdr, Referer: 'https://i.chaoxing.com/base', 'Accept-Language': 'zh-CN,zh;q=0.9' },
        redirect: 'follow',
        signal: AbortSignal.timeout(20000),
      });
    } catch (e) {
      console.log(`${host} [${mode}] 请求失败: ${e.message}`);
      continue;
    }
    const body = await r.text();
    const p = probe(body);
    console.log(
      `${host.padEnd(22)} [${mode.padEnd(5)}] cookie=${String(n).padStart(2)}  http=${r.status}` +
      `  登录页=${p.isLogin ? '是' : '否'}  title="${p.title}"  enc包=${p.keys}  长度=${body.length}`
    );
    await new Promise((res) => setTimeout(res, 400));
  }
}
