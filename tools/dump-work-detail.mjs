#!/usr/bin/env node
/**
 * 修正题型识别（实际是 "1. (论述题)" 半角括号，不是【】），
 * 并把一门课的所有未交作业逐个打开，找含选项的客观题，完整 dump 结构。
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';
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
  if (m.method === 'Network.requestWillBeSent') {
    const u = m.params.request.url;
    if (u.includes('/mooc2/work/list')) workReqs.push(u);
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
const jsonIn = async (ctx, expr) => { const v = await evalIn(ctx, expr); try { return JSON.parse(v || '{}'); } catch { return {}; } };

await send('Page.enable'); await send('Runtime.enable'); await send('Network.enable');

async function frameTree() {
  const t = await safe(() => send('Page.getFrameTree'), null);
  const out = [];
  const walk = (n, d) => { out.push({ id: n.frame.id, url: n.frame.url, depth: d }); (n.childFrames || []).forEach((c) => walk(c, d + 1)); };
  if (t?.frameTree) walk(t.frameTree, 0);
  return out;
}

// 抓取单份作业的题目结构
const EXTRACT = `
(function(){
  var timu = Array.from(document.querySelectorAll('.TiMu'));
  var qs = timu.map(function(t, i){
    var titleEl = t.querySelector('.newZy_TItle, .Zy_TItle, .mark_name');
    var raw = titleEl ? (titleEl.innerText||'').replace(/\\s+/g,' ').trim() : '';
    var m = raw.match(/[(（]\\s*([^)）]{2,6})\\s*[)）]/);
    var type = m ? m[1] : '';
    var opts = Array.from(t.querySelectorAll('li')).map(function(li){
      var num = li.querySelector('.num_option');
      if (!num) return null;
      return { show:(num.innerText||'').trim().slice(0,3), data:(num.getAttribute('data')||''), text:(li.innerText||'').replace(/\\s+/g,' ').trim().slice(0,50) };
    }).filter(Boolean);
    var ansFields = Array.from(t.querySelectorAll('input')).map(function(x){return x.name;}).filter(function(n){return /^(answer|answertype|tiankongsize)/.test(n);});
    return {
      i: i+1, type: type, raw: raw.slice(0, 120),
      opts: opts, fields: ansFields,
      editors: t.querySelectorAll('textarea, .edui-body-container').length
    };
  });
  return JSON.stringify({ n: timu.length, qs: qs, secret: document.querySelectorAll('.font-cxsecret').length });
})()
`;

const COURSES = [
  { courseId: '265800072', classId: '', cpi: '433049509' },
  { courseId: '265795351', classId: '', cpi: '433049509' },
];

import { readFileSync } from 'node:fs';
const report = JSON.parse(readFileSync('.workbuddy/research/report.json', 'utf8'));
const byId = new Map((report.courses || []).map((c) => [c.courseId, c]));

let best = null;

for (const c0 of COURSES) {
  const c = byId.get(c0.courseId);
  if (!c) continue;
  console.log(`\n########## 课程 ${c.courseId} ##########`);
  workReqs = [];
  await safe(() => send('Page.navigate', {
    url: `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${c.courseId}&clazzid=${c.classId}&cpi=${c.cpi}&ismooc2=1&v=2`,
  }));
  await sleep(5000);
  await safe(() => evalIn(null, `(function(){var a=document.querySelector('a[title="作业"]');if(a)a.click();})()`));
  await sleep(4500);
  if (!workReqs[0]) { console.log('  没抓到列表'); continue; }

  await safe(() => send('Page.navigate', { url: workReqs[0] }));
  await sleep(4000);
  const rows = await jsonIn(null, `(function(){return JSON.stringify(Array.from(document.querySelectorAll('li[data]')).map(function(li){return {label: li.getAttribute('aria-label')||'', url: li.getAttribute('data')||''};}));})()`);
  const arr = Array.isArray(rows) ? rows : [];
  const pending = arr.filter((r) => /未交/.test(r.label));
  console.log(`  作业 ${arr.length} 条，未交 ${pending.length} 条`);

  for (const w of pending.slice(0, 5)) {
    console.log(`\n  -- ${w.label.slice(0, 44)}`);
    ctxByFrame.clear();
    await safe(() => send('Page.navigate', { url: w.url }));
    await sleep(5000);
    const frames = await frameTree();
    let got = null;
    for (const f of frames) {
      const ctx = ctxByFrame.get(f.id);
      if (ctx == null) continue;
      const d = await jsonIn(ctx, EXTRACT);
      if ((d.n || 0) > 0) { got = { d, frame: f }; break; }
    }
    if (!got) { console.log('     无题目（可能需先开始作答）'); continue; }

    const types = [...new Set((got.d.qs || []).map((q) => q.type || '?').filter(Boolean))];
    const optCount = (got.d.qs || []).reduce((n, q) => n + (q.opts || []).length, 0);
    console.log(`     题目 ${got.d.n} 道 | 题型 ${types.join('/')} | 选项数 ${optCount} | 反爬字体 ${got.d.secret}`);
    (got.d.qs || []).slice(0, 3).forEach((q) => {
      console.log(`       ${q.i}. [${q.type || '?'}] ${q.raw.slice(0, 80)}`);
      if (q.opts.length) {
        q.opts.slice(0, 6).forEach((o) => console.log(`          显示[${o.show}] data[${o.data}] ${o.text}`));
      }
      if (q.fields.length) console.log(`          answer 域: ${q.fields.join(', ')}`);
    });

    if (optCount > 0 && !best) {
      best = { course: c, work: w, ...got };
      console.log('\n     >>> 找到含选项的客观题，停止扫描');
      break;
    }
  }
  if (best) break;
}

if (best) {
  console.log('\n\n============ 客观题完整样本 ============');
  console.log('课程', best.course.courseId, '作业', best.work.label);
  const d = await jsonIn(best.frame ? ctxByFrame.get(best.frame.id) : null, EXTRACT);
  const first = (d.qs || []).find((q) => (q.opts || []).length > 0);
  if (first) {
    console.log('题号:', first.i, ' 题型:', first.type);
    console.log('题干:', first.raw);
    console.log('选项（显示字母 vs data 原始序号）:');
    first.opts.forEach((o) => console.log(`   ${o.show}  data=${o.data}  ${o.text}`));
    console.log('answer 相关隐藏域:', first.fields.join(', '));
    console.log('可编辑控件:', first.editors);
  }
} else {
  console.log('\n这批作业全是单题主观题，未出现选择题选项。');
}

ws.close();
