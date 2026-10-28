#!/usr/bin/env node
/**
 * 验证：作业详情/作答页的题目结构 + 是否有字体反爬。
 *
 * 流程：
 *  1) 打开课程页 → 点「作业」→ 抓 work/list 真实 URL
 *  2) 打开 work/list → 取每条的完整 data 直链
 *  3) 打开第一条「未交」作业 → 逐层 iframe 找 .TiMu
 *  4) 打印题型/题干/选项/填空数 + 隐藏域 qid + 字体反爬检测
 */
const CDP = process.env.CDP || 'http://127.0.0.1:9222';
const list = await (await fetch(`${CDP}/json/list`)).json();
const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
const ws = new WebSocket(page.webSocketDebuggerUrl);

let id = 0;
const waiting = new Map();
let workReqs = [];
const ctxByFrame = new Map(); // frameId -> executionContextId

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
const safe = async (fn, fb = null) => { try { return await fn(); } catch (e) { console.log('    [warn]', e.message); return fb; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function evalIn(ctxId, expr) {
  const params = { expression: expr, returnByValue: true };
  if (ctxId != null) params.contextId = ctxId;
  const r = await safe(() => send('Runtime.evaluate', params), null);
  if (r?.exceptionDetails) return null;
  return r?.result?.value;
}
async function jsonIn(ctxId, expr) {
  const v = await evalIn(ctxId, expr);
  try { return JSON.parse(v || '{}'); } catch { return {}; }
}

await send('Page.enable');
await send('Runtime.enable');
await send('Network.enable');

const course = { courseId: '265965087', classId: '152023513', cpi: '433049509' };

// ---------- 1. 课程页 → 点作业 ----------
console.log('=== 1. 打开课程页并点「作业」 ===');
await safe(() => send('Page.navigate', {
  url: `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${course.courseId}&clazzid=${course.classId}&cpi=${course.cpi}&ismooc2=1&v=2`,
}));
await sleep(6000);
workReqs = [];
await safe(() => evalIn(null, `(function(){var a=document.querySelector('a[title="作业"]');if(a)a.click();})()`));
await sleep(5500);
const workListUrl = workReqs[0];
if (!workListUrl) { console.log('  没抓到 work/list，退出'); ws.close(); process.exit(1); }
console.log('  work/list 已捕获');

// ---------- 2. 作业列表 → 完整 data 直链 ----------
console.log('\n=== 2. 打开作业列表，取完整直链 ===');
await safe(() => send('Page.navigate', { url: workListUrl }));
await sleep(5000);

const rows = await jsonIn(null, `
(function(){
  var lis = Array.from(document.querySelectorAll('li[data]')).map(function(li){
    return { label: li.getAttribute('aria-label')||'', url: li.getAttribute('data')||'' };
  });
  return JSON.stringify(lis);
})()
`);

const list2 = Array.isArray(rows) ? rows : [];
console.log(`  共 ${list2.length} 条`);
list2.forEach((r, i) => console.log(`  [${i}] ${r.label}`));
if (!list2.length) { console.log('  列表为空'); ws.close(); process.exit(0); }

const target = list2.find((r) => /未交/.test(r.label)) || list2[0];
console.log('\n  选中:', target.label);
console.log('  直链:', target.url.slice(0, 260));

// ---------- 3. 打开作答页 ----------
console.log('\n=== 3. 打开作业详情/作答页 ===');
ctxByFrame.clear();
await safe(() => send('Page.navigate', { url: target.url }));
await sleep(6000);

async function frameTree() {
  const t = await safe(() => send('Page.getFrameTree'), null);
  const out = [];
  const walk = (n, depth) => {
    out.push({ id: n.frame.id, url: n.frame.url, depth });
    (n.childFrames || []).forEach((c) => walk(c, depth + 1));
  };
  if (t?.frameTree) walk(t.frameTree, 0);
  return out;
}

let frames = await frameTree();
console.log('  帧数量:', frames.length);
frames.forEach((f) => console.log(`    ${'  '.repeat(f.depth)}[${f.depth}] ${f.url.slice(0, 130)}`));

// 可能需要点「开始答题 / 继续作答」
async function tryStart() {
  for (const f of frames) {
    const ctx = ctxByFrame.get(f.id);
    if (ctx == null) continue;
    const hit = await evalIn(ctx, `
    (function(){
      var nodes = Array.from(document.querySelectorAll('a,button,div,span,li'));
      var t = nodes.filter(function(e){
        var s=(e.innerText||'').trim();
        return /^(开始答题|开始做题|继续作答|进入作业)$/.test(s);
      })[0];
      if (!t) return '';
      t.click();
      return t.innerText.trim();
    })()
    `);
    if (hit) return `${hit} @ ${f.url.slice(0, 60)}`;
  }
  return '';
}
const started = await tryStart();
if (started) {
  console.log('  点击了:', started);
  await sleep(6000);
  frames = await frameTree();
  console.log('  重新扫描帧:', frames.length);
}

// ---------- 4. 在每帧里找题目 ----------
console.log('\n=== 4. 逐帧找 .TiMu ===');
let found = 0;
for (const f of frames) {
  const ctx = ctxByFrame.get(f.id);
  if (ctx == null) { console.log(`    [跳过] 无执行上下文 ${f.url.slice(0, 70)}`); continue; }
  const info = await jsonIn(ctx, `
  (function(){
    var timu = document.querySelectorAll('.TiMu');
    var secret = document.querySelectorAll('.font-cxsecret').length;
    var face = /font-cxsecret/.test(document.documentElement.innerHTML);
    return JSON.stringify({
      timu: timu.length, secret: secret, hasSecretFont: face,
      qids: Array.from(document.querySelectorAll('input[name^="answertype"]')).slice(0,3).map(function(i){
        return i.name + '=' + i.value;
      }),
      body: (document.body?document.body.innerText:'').slice(0,80).replace(/\\s+/g,' ')
    });
  })()
  `);
  console.log(`    [${f.depth}] TiMu=${info.timu ?? '?'}  反爬字体=${info.hasSecretFont ? '有' : '无'}  正文="${(info.body || '').slice(0, 50)}"`);
  if ((info.timu || 0) > 0) {
    found = info.timu;
    console.log(`\n    >>> 题目在这一帧：${f.url.slice(0, 160)}`);

    const detail = await jsonIn(ctx, `
    (function(){
      var arr = Array.from(document.querySelectorAll('.TiMu')).slice(0,4).map(function(t, idx){
        var titleEl = t.querySelector('.newZy_TItle, .Zy_TItle, .mark_name');
        var title = titleEl ? (titleEl.innerText||'').replace(/\\s+/g,' ').trim().slice(0,110) : '';
        var stemEl = t.querySelector('.Zy_TItle .clearfix, .mark_name + div, .TiMu .clearfix');
        var opts = Array.from(t.querySelectorAll('li.num_option, .option li, li')).slice(0,8).map(function(li){
          var num = li.querySelector('.num_option');
          return {
            show: num ? (num.innerText||'').trim().slice(0,2) : '',
            text: (li.innerText||'').replace(/\\s+/g,' ').trim().slice(0,40)
          };
        });
        var hidden = Array.from(t.querySelectorAll('input[type=hidden]')).slice(0,6).map(function(i){
          return i.name + (i.value ? '=' + i.value.slice(0,12) : '');
        });
        var blanks = t.querySelectorAll('textarea, .edui-body-container, input[type=text]').length;
        return { idx: idx+1, data: t.getAttribute('data'), title: title, opts: opts, hidden: hidden, inputs: blanks };
      });
      return JSON.stringify(arr);
    })()
    `);
    const arr = Array.isArray(detail) ? detail : [];
    arr.forEach((q) => {
      console.log(`\n    题${q.idx}  data=${q.data}`);
      console.log(`      标题: ${q.title}`);
      console.log(`      选项: ${q.opts.filter((o) => o.text).map((o) => `${o.show}.${o.text}`).join(' | ').slice(0, 220)}`);
      console.log(`      隐藏域: ${q.hidden.join('  ')}`);
      console.log(`      输入控件数: ${q.inputs}`);
    });
    break;
  }
}

if (!found) {
  console.log('\n  所有帧都没有 .TiMu —— 页面可能需要先登录/先开始答题，或结构不同');
  const dump = await jsonIn(null, `JSON.stringify({href: location.href, title: document.title, body: (document.body?document.body.innerText:'').slice(0,200).replace(/\\s+/g,' ')})`);
  console.log('  顶层:', dump.title, '|', dump.body);
}

ws.close();
