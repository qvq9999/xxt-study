#!/usr/bin/env node
/**
 * 在真实浏览器里把「课程 → 作业列表」走完，并打印解析结果。
 *
 * 为什么用浏览器而不是裸 HTTP：
 * 实测同一条 stucoursemiddle URL，浏览器 302 到课程页并带出 enc，
 * 裸 fetch 即使 Cookie 完全一致也返回登录页 —— 说明服务端在按请求特征（TLS/头）区分，
 * 纯 HTTP 这条路走不通，必须在 WebView/浏览器上下文里做。
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
const safe = async (fn, fallback = null) => { try { return await fn(); } catch (e) { console.log('  [warn]', e.message); return fallback; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

await send('Page.enable');
await send('Runtime.enable');

const evalJs = async (expr) => {
  const r = await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true }), null);
  return r?.result?.value;
};

const course = { courseId: '245030810', classId: '152663533', cpi: '433049509', name: '中华民族共同体概论' };

console.log('=== 第一步：打开课程中间页 ===');
const middle = `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`;
await safe(() => send('Page.navigate', { url: middle }));
await sleep(5000);

const afterMiddle = await evalJs(`JSON.stringify({
  href: location.href,
  title: document.title,
  workLinks: Array.from(document.querySelectorAll('a[href*=work],iframe[src*=work],li[data-url*=work]')).slice(0,6).map(e=>e.href||e.src||e.getAttribute('data-url')),
  tabTexts: Array.from(document.querySelectorAll('.nav li,.course-nav li,.tab li')).slice(0,12).map(e=>e.innerText.trim().replace(/\\s+/g,''))
})`);
const m1 = JSON.parse(afterMiddle || '{}');
console.log('  落地:', String(m1.href || '').slice(0, 200));
console.log('  标题:', m1.title);
console.log('  页内作业相关链接:', (m1.workLinks || []).length ? m1.workLinks.map((u) => String(u).slice(0, 160)) : '无');
console.log('  标签:', (m1.tabTexts || []).join(' | ').slice(0, 120));

// 从落地 URL 里取 enc（课程页 URL 自带 enc，且它就是作业列表要用的 stuenc）
const encInUrl = String(m1.href || '').match(/[?&]enc=([0-9a-f]+)/)?.[1] || '';
console.log('  课程页 enc:', encInUrl || '未取到');

console.log('\n=== 第二步：直接打开作业列表 ===');
// 课程页上的「作业」标签通常指向 mooc1/mooc2 的 work/list；先试最稳的一种拼法
const candidates = [];
if (encInUrl) {
  candidates.push(
    `https://mooc1.chaoxing.com/mooc2/work/list?courseId=${course.courseId}&classId=${course.classId}&cpi=${course.cpi}&ut=s&t=${Date.now()}&stuenc=${encInUrl}&enc=${encInUrl}`
  );
}
for (const u of (m1.workLinks || [])) if (u && /work/.test(String(u))) candidates.push(String(u));

if (!candidates.length) {
  console.log('  没有可用的作业列表 URL，停在这一步');
  ws.close();
  process.exit(0);
}

for (const url of candidates.slice(0, 3)) {
  console.log('\n  尝试:', url.slice(0, 180));
  await safe(() => send('Page.navigate', { url }));
  await sleep(4500);

  const info = await evalJs(`(function(){
    var lis = Array.from(document.querySelectorAll('li[data]')).filter(function(li){
      return (li.getAttribute('data')||'').indexOf('chaoxing.com') >= 0;
    });
    var rows = lis.slice(0,5).map(function(li){
      return { label: li.getAttribute('aria-label') || '', data: (li.getAttribute('data')||'').slice(0,120) };
    });
    var anyLi = document.querySelectorAll('li').length;
    return JSON.stringify({
      href: location.href, title: document.title,
      isLogin: /用户登录/.test(document.documentElement.innerHTML),
      workRows: lis.length, anyLi: anyLi, samples: rows,
      textHead: (document.body?document.body.innerText:'').slice(0,100).replace(/\\s+/g,' ')
    });
  })()`);
  const d = JSON.parse(info || '{}');
  console.log('    落地:', String(d.href || '').slice(0, 160));
  console.log('    标题:', d.title, '| 登录页:', d.isLogin ? '是' : '否');
  console.log('    作业行数:', d.workRows, '（页面 li 总数', d.anyLi, '）');
  (d.samples || []).forEach((s) => console.log('      -', s.label, '|', s.data.slice(0, 80)));
  console.log('    正文:', String(d.textHead || '').slice(0, 90));
  if (d.workRows > 0) {
    console.log('\n  >>> 通了：作业列表可以在浏览器上下文里拿到');
    break;
  }
}

ws.close();
