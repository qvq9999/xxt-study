#!/usr/bin/env node
/**
 * 让"已登录的浏览器"自己去踩路，记录每一跳的真实落地页。
 *
 * 目的：搞清 stucoursemiddle 为什么返回登录页，以及真实的课程页 / 作业列表 URL 长什么样
 * （特别是 enc / stuenc 从哪来）。
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
    setTimeout(() => waiting.has(mid) && (waiting.delete(mid), rej(new Error('timeout'))), 25000);
  });
};

await send('Page.enable');
await send('Runtime.enable');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function evalJs(expr) {
  const { result } = await send('Runtime.evaluate', {
    expression: expr,
    returnByValue: true,
    awaitPromise: false,
  });
  return result?.value;
}

const course = { courseId: '245030810', classId: '152663533', cpi: '433049509' };

const steps = [
  ['个人主页', 'https://i.chaoxing.com/base'],
  ['互动（课程卡片）', 'https://mooc2-ans.chaoxing.com/mooc2-ans/visit/interaction'],
  [
    '中间页',
    `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`,
  ],
  [
    '课程主页（新版）',
    `https://mooc2-ans.chaoxing.com/mooc2-ans/mycourse/stu?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&pageHeader=8`,
  ],
];

for (const [label, url] of steps) {
  console.log(`\n===== ${label} =====`);
  console.log('  请求:', url);
  try {
    await send('Page.navigate', { url });
  } catch (e) {
    console.log('  导航失败:', e.message);
    continue;
  }
  await sleep(4500);

  const info = await evalJs(`(function(){
    var frames = [];
    var fs = document.querySelectorAll('iframe');
    for (var i=0;i<fs.length && i<8;i++) frames.push(fs[i].src || '(空)');
    var body = document.body ? document.body.innerText.slice(0,120).replace(/\\s+/g,' ') : '';
    return JSON.stringify({
      href: location.href,
      title: document.title,
      isLogin: /用户登录|请输入密码/.test(document.body ? document.body.innerHTML : ''),
      frames: frames,
      textHead: body
    });
  })()`);

  const d = JSON.parse(info || '{}');
  console.log('  落地:', String(d.href).slice(0, 160));
  console.log('  标题:', d.title, '| 是否登录页:', d.isLogin ? '是' : '否');
  if (d.frames?.length) {
    d.frames.forEach((f, i) => console.log(`  iframe[${i}]:`, String(f).slice(0, 180)));
  }
  console.log('  正文开头:', String(d.textHead || '').slice(0, 100));
}

ws.close();
