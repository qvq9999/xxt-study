#!/usr/bin/env node
/**
 * 打开一份单选题作业，把 .TiMu 的原始 outerHTML 存盘，
 * 并统计页面里 option/choice/answer 相关元素的真实 class 结构。
 */
import { writeFileSync, mkdirSync } from 'node:fs';

const CDP = process.env.CDP || 'http://127.0.0.1:9222';
const OUT = '.workbuddy/research/samples';
mkdirSync(OUT, { recursive: true });

const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const ws = new WebSocket(page.webSocketDebuggerUrl);

let id = 0;
const waiting = new Map();
let workReqs = [];
const ctxByFrame = new Map();

await new Promise((r, j) => { ws.addEventListener('open', r); ws.addEventListener('error', j); });
ws.addEventListener('message', (ev) => {
  const m = JSON.parse(ev.data);
  if (m.id && waiting.has(m.id)) {
    const { resolve, reject } = waiting.get(m.id);
    waiting.delete(m.id);
    m.error ? reject(new Error(m.error.message)) : resolve(m.result);
    return;
  }
  if (m.method === 'Network.requestWillBeSent' && m.params.request.url.includes('/mooc2/work/list')) {
    workReqs.push(m.params.request.url);
  }
  if (m.method === 'Runtime.executionContextCreated') {
    const c = m.params.context;
    const fid = c.auxData?.frameId;
    if (fid && c.auxData?.isDefault) ctxByFrame.set(fid, c.id);
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
const safe = async (fn, fb = null) => { try { return await fn(); } catch { return fb; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const evalIn = async (ctx, expr) => (await safe(() => send('Runtime.evaluate', { expression: expr, returnByValue: true, ...(ctx != null ? { contextId: ctx } : {}) }), null))?.result?.value;

await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');

const course = { courseId: '265800072', classId: '', cpi: '433049509' };
// 从 report.json 补 classId
const { readFileSync } = await import('node:fs');
const report = JSON.parse(readFileSync('.workbuddy/research/report.json', 'utf8'));
const c = (report.courses || []).find((x) => x.courseId === course.courseId);
course.classId = c.classId;

console.log('=== 进入课程 → 作业列表 ===');
workReqs = [];
await safe(() => send('Page.navigate', {
  url: `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`,
}));
await sleep(5000);
await safe(() => evalIn(null, `(function(){var a=document.querySelector('a[title="作业"]');if(a)a.click();})()`));
await sleep(4500);
await safe(() => send('Page.navigate', { url: workReqs[0] }));
await sleep(4500);

const rowsRaw = await evalIn(null, `JSON.stringify(Array.from(document.querySelectorAll('li[data]')).map(function(li){return {label: li.getAttribute('aria-label')||'', url: li.getAttribute('data')||''};}))`);
const rows = JSON.parse(rowsRaw || '[]');
const target = rows.find((r) => /蒙版/.test(r.label)) || rows.find((r) => /未交/.test(r.label));
console.log('目标作业:', target.label);
console.log('直链:', target.url.slice(0, 260));

console.log('\n=== 打开作答页 ===');
ctxByFrame.clear();
await safe(() => send('Page.navigate', { url: target.url }));
await sleep(6500);

const t = await safe(() => send('Page.getFrameTree'), null);
const frames = [];
const walk = (n, d) => { frames.push({ id: n.frame.id, url: n.frame.url, depth: d }); (n.childFrames || []).forEach((x) => walk(x, d + 1)); };
if (t?.frameTree) walk(t.frameTree, 0);
frames.forEach((f) => console.log(`  [${f.depth}] ${f.url.slice(0, 120)}`));

let savedPath = '';
for (const f of frames) {
  const ctx = ctxByFrame.get(f.id);
  if (ctx == null) continue;
  const probe = await evalIn(ctx, `JSON.stringify({timu: document.querySelectorAll('.TiMu').length, body: (document.body?document.body.innerText:'').slice(0,60)})`);
  const p = JSON.parse(probe || '{}');
  if (!p.timu) continue;

  console.log(`\n题目在帧 ${f.url.slice(0, 100)}  TiMu=${p.timu}`);

  const outer = await evalIn(ctx, `(function(){var t=document.querySelector('.TiMu'); return t? t.outerHTML : '';})()`);
  savedPath = `${OUT}/work-question.html`;
  writeFileSync(savedPath, outer || '', 'utf8');
  console.log('  已存 .TiMu outerHTML:', savedPath, `(${(outer || '').length} 字符)`);

  const stats = await evalIn(ctx, `
  (function(){
    var counts = {};
    Array.from(document.querySelectorAll('*')).forEach(function(e){
      (e.className && typeof e.className === 'string' ? e.className : '').split(/\\s+/).forEach(function(c){
        if (/option|choice|answer|TiMu|Zy_|mark_/i.test(c)) counts[c] = (counts[c]||0)+1;
      });
    });
    var inputs = Array.from(document.querySelectorAll('input')).map(function(i){return i.type+':'+i.name;}).slice(0,40);
    return JSON.stringify({ classes: counts, inputs: inputs });
  })()
  `);
  const s = JSON.parse(stats || '{}');
  console.log('  相关 class 统计:');
  Object.entries(s.classes || {}).sort((a, b) => b[1] - a[1]).slice(0, 18)
    .forEach(([k, v]) => console.log(`    ${k} ×${v}`));
  console.log('  input（前 20）:');
  (s.inputs || []).slice(0, 20).forEach((x) => console.log('    ', x));

  // 打印 .TiMu 结构骨架（标签+class，不带文本）
  const skeleton = await evalIn(ctx, `
  (function(){
    var root = document.querySelector('.TiMu');
    if (!root) return '';
    var out = [];
    (function rec(n, d){
      if (d > 3) return;
      out.push('  '.repeat(d) + n.tagName.toLowerCase() + (n.className && typeof n.className==='string' ? '.'+n.className.trim().split(/\\s+/).join('.') : ''));
      Array.from(n.children).slice(0, 8).forEach(function(ch){ rec(ch, d+1); });
    })(root, 0);
    return out.join('\\n');
  })()
  `);
  console.log('\n  .TiMu 结构骨架:');
  console.log(String(skeleton || '').split('\n').slice(0, 40).map((l) => '    ' + l).join('\n'));
  break;
}

if (!savedPath) console.log('\n没找到 .TiMu');
ws.close();
