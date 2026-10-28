#!/usr/bin/env node
/**
 * 扫多门课，找一份含客观题（单选/多选/判断/填空）的作业，把题目结构完整验掉。
 * 找到即详细 dump：题型、qid、题干、选项（显示字母 + data 原始序号）、答案输入域。
 */
import { readFileSync } from 'node:fs';

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

const report = JSON.parse(readFileSync('.workbuddy/research/report.json', 'utf8'));
const courses = (report.courses || []).filter((c) => c.cpi);
const skip = new Set(['245030810', '265956858', '265965087']);
const queue = courses.filter((c) => !skip.has(c.courseId)).slice(0, 8);

async function frameTree() {
  const t = await safe(() => send('Page.getFrameTree'), null);
  const out = [];
  const walk = (n, d) => { out.push({ id: n.frame.id, url: n.frame.url, depth: d }); (n.childFrames || []).forEach((c) => walk(c, d + 1)); };
  if (t?.frameTree) walk(t.frameTree, 0);
  return out;
}

const TYPE_TAGS = ['单选题', '多选题', '判断题', '填空题', '论述题', '简答题', '名词解释', '计算题'];

let foundWork = null;

for (const c of queue) {
  console.log(`\n--- 课程 ${c.courseId} ---`);
  workReqs = [];
  await safe(() => send('Page.navigate', {
    url: `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${c.courseId}&clazzid=${c.classId}&cpi=${c.cpi}&ismooc2=1&v=2`,
  }));
  await sleep(5000);
  const clicked = await evalIn(null, `(function(){var a=document.querySelector('a[title="作业"]');if(!a)return 'no';a.click();return 'ok';})()`);
  await sleep(4500);
  if (clicked !== 'ok' || !workReqs[0]) { console.log('  没拿到作业列表'); continue; }

  await safe(() => send('Page.navigate', { url: workReqs[0] }));
  await sleep(4000);
  const rows = await jsonIn(null, `(function(){return JSON.stringify(Array.from(document.querySelectorAll('li[data]')).map(function(li){return {label: li.getAttribute('aria-label')||'', url: li.getAttribute('data')||''};}));})()`);
  const arr = Array.isArray(rows) ? rows : [];
  const pending = arr.filter((r) => /未交/.test(r.label));
  console.log(`  作业 ${arr.length} 条，未交 ${pending.length} 条`);
  if (!pending.length) continue;

  const w = pending[0];
  console.log('  看:', w.label.slice(0, 40));
  ctxByFrame.clear();
  await safe(() => send('Page.navigate', { url: w.url }));
  await sleep(5500);

  const frames = await frameTree();
  let found = null;
  for (const f of frames) {
    const ctx = ctxByFrame.get(f.id);
    if (ctx == null) continue;
    const d = await jsonIn(ctx, `
    (function(){
      var timu = Array.from(document.querySelectorAll('.TiMu'));
      var types = timu.map(function(t){
        var el = t.querySelector('.newZy_TItle, .Zy_TItle');
        var s = el ? (el.innerText||'') : '';
        var m = s.match(/【([^】]+)】/);
        return m ? m[1] : (t.getAttribute('data')||'?');
      });
      return JSON.stringify({ n: timu.length, types: types });
    })()
    `);
    if ((d.n || 0) > 0) { found = { ctx, frame: f, info: d }; break; }
  }
  if (!found) { console.log('  没找到题目'); continue; }

  const counts = {};
  (found.info.types || []).forEach((t) => { counts[t] = (counts[t] || 0) + 1; });
  console.log(`  题目 ${found.info.n} 道，题型分布:`, JSON.stringify(counts));

  const hasObjective = Object.keys(counts).some((t) => TYPE_TAGS.slice(0, 4).includes(t));
  if (hasObjective) { foundWork = { course: c, work: w, ...found }; break; }
  console.log('  全是主观题，继续找');
}

if (!foundWork) {
  console.log('\n前 8 门课没找到含客观题的未交作业。已验结论：作业页无字体反爬、题干可读、.TiMu 结构稳定。');
  ws.close(); process.exit(0);
}

console.log('\n\n================ 找到含客观题的作业 ================');
console.log('课程:', foundWork.course.courseId, ' 作业:', foundWork.work.label);
console.log('直链:', foundWork.work.url.slice(0, 240));

const detail = await jsonIn(foundWork.ctx, `
(function(){
  var arr = Array.from(document.querySelectorAll('.TiMu')).map(function(t, i){
    var titleEl = t.querySelector('.newZy_TItle, .Zy_TItle');
    var type = '';
    var stem = '';
    if (titleEl) {
      var raw = (titleEl.innerText || '').replace(/\\s+/g,' ').trim();
      var m = raw.match(/【([^】]+)】/);
      type = m ? m[1] : '';
      stem = raw.replace(/^\\s*\\d+[.、]\\s*/, '').slice(0, 150);
    }
    var opts = Array.from(t.querySelectorAll('li')).map(function(li){
      var num = li.querySelector('.num_option');
      if (!num) return null;
      return {
        show: (num.innerText||'').trim().slice(0,3),
        data: (num.getAttribute('data')||'').slice(0,3),
        text: (li.innerText||'').replace(/\\s+/g,' ').trim().slice(0, 45)
      };
    }).filter(Boolean);
    var hidden = Array.from(t.querySelectorAll('input[type=hidden]')).map(function(x){return x.name;}).filter(function(n){return /^answer/.test(n);});
    var editors = t.querySelectorAll('textarea, input[type=text], .edui-body-container').length;
    return { i: i+1, type: type, stem: stem, opts: opts, hidden: hidden, editors: editors };
  });
  return JSON.stringify(arr);
})()
`);

const qs = Array.isArray(detail) ? detail : [];
qs.slice(0, 6).forEach((q) => {
  console.log(`\n--- 题${q.i} 【${q.type}】 ---`);
  console.log('  题干:', q.stem);
  if (q.opts.length) {
    console.log('  选项:');
    q.opts.forEach((o) => console.log(`    显示[${o.show}] data[${o.data}] ${o.text}`));
  }
  console.log('  隐藏域:', q.hidden.join(', '));
  console.log('  可编辑控件:', q.editors);
});

ws.close();
