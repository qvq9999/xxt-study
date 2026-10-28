#!/usr/bin/env node
/**
 * 在课程页里真的点一下「作业」标签，把作业列表 iframe 的真实 URL（含正确 enc）抠出来。
 *
 * 背景：课程页 URL 里的 enc 拼到 work/list 上会得到「无权限的操作！」，
 * 说明作业标签用的是另一个 enc，只能从点击后的 iframe src 里拿。
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

async function raw(expr) {
  const r = await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true }), null);
  return r?.result?.value;
}

async function asJson(expr) {
  const v = await raw(expr);
  try { return JSON.parse(v || '{}'); } catch { return {}; }
}

await send('Page.enable');
await send('Runtime.enable');

const course = { courseId: '245030810', classId: '152663533', cpi: '433049509' };
const middle = `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`;

console.log('=== 打开课程页 ===');
await safe(() => send('Page.navigate', { url: middle }));
await sleep(5500);

const before = await asJson(`
(function(){
  var all = Array.from(document.querySelectorAll('*'));
  var workish = all.filter(function(e){
    var s = (e.getAttribute('onclick')||'') + ' ' + (e.getAttribute('href')||'') + ' ' + (e.getAttribute('data-url')||'');
    return /work/i.test(s);
  }).slice(0,10).map(function(e){
    return { tag: e.tagName, text: (e.innerText||'').trim().slice(0,20),
             onclick: (e.getAttribute('onclick')||'').slice(0,90),
             href: (e.getAttribute('href')||'').slice(0,90) };
  });
  var navTexts = Array.from(document.querySelectorAll('li,a,span'))
    .map(function(e){return (e.innerText||'').trim();})
    .filter(function(t){return t && t.length<=4;});
  return JSON.stringify({ href: location.href, workish: workish, nav: navTexts.slice(0,25) });
})()
`);

console.log('  落地:', String(before.href || '').slice(0, 150));
console.log('  页内 /work/ 相关元素:');
(before.workish || []).forEach((e) => console.log(`    <${e.tag}> "${e.text}"  onclick="${e.onclick}" href="${e.href}"`));
console.log('  短文本标签:', (before.nav || []).join(' | ').slice(0, 140));

console.log('\n=== 点「作业」标签 ===');
const clicked = await raw(`
(function(){
  var nodes = Array.from(document.querySelectorAll('a,li,span,div'));
  var target = nodes.filter(function(e){
    return (e.innerText||'').trim() === '作业' && e.children.length === 0;
  })[0];
  if (!target) return 'not-found';
  var clickable = target.closest('a') || target.closest('li') || target;
  clickable.click();
  return 'clicked:' + clickable.tagName + ':' + (clickable.getAttribute('onclick')||'').slice(0,60);
})()
`);
console.log('  点击结果:', clicked);
await sleep(5000);

const after = await asJson(`
(function(){
  var frames = Array.from(document.querySelectorAll('iframe')).map(function(f){return f.src||'(空)';});
  var lis = Array.from(document.querySelectorAll('li[data]')).filter(function(li){
    return (li.getAttribute('data')||'').indexOf('chaoxing.com') >= 0;
  });
  return JSON.stringify({
    href: location.href,
    frames: frames,
    workFrames: frames.filter(function(s){return /work/i.test(s);}),
    workRows: lis.length,
    textHead: (document.body?document.body.innerText:'').slice(0,120).replace(/\\s+/g,' ')
  });
})()
`);

console.log('  当前地址:', String(after.href || '').slice(0, 150));
console.log('  iframe:');
(after.frames || []).forEach((f, i) => console.log(`    [${i}]`, String(f).slice(0, 200)));
console.log('  正文:', String(after.textHead || '').slice(0, 110));

const wf = (after.workFrames || [])[0];
if (wf) {
  console.log('\n=== 单独打开作业 iframe 地址 ===');
  await safe(() => send('Page.navigate', { url: wf }));
  await sleep(4500);
  const info = await asJson(`
  (function(){
    var lis = Array.from(document.querySelectorAll('li[data]')).filter(function(li){
      return (li.getAttribute('data')||'').indexOf('chaoxing.com') >= 0;
    });
    return JSON.stringify({
      href: location.href, title: document.title,
      rows: lis.length,
      samples: lis.slice(0,5).map(function(li){
        return (li.getAttribute('aria-label')||'') + ' => ' + (li.getAttribute('data')||'').slice(0,90);
      }),
      textHead: (document.body?document.body.innerText:'').slice(0,120).replace(/\\s+/g,' ')
    });
  })()
  `);
  console.log('  标题:', info.title, '| 作业行数:', info.rows);
  (info.samples || []).forEach((s) => console.log('    -', s));
  console.log('  正文:', String(info.textHead || '').slice(0, 110));
  console.log('\n  作业列表完整 URL:');
  console.log('  ', String(info.href || wf).slice(0, 320));
}

ws.close();
