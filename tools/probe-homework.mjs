#!/usr/bin/env node
/**
 * 学习通作业链路探针（M0 专用）
 *
 * 目的：在写任何业务代码之前，用你自己的账号把「课程 -> enc 包 -> 作业列表 -> 作业详情」
 *      这条链接通一次，把每一跳的真实响应落盘，作为后续解析器的事实依据。
 *
 * 用法：
 *   1) 把浏览器里登录学习通后的整串 Cookie 存到 .workbuddy/research/cookie.txt
 *      （或直接设环境变量 CX_COOKIE）
 *   2) node tools/probe-homework.mjs
 *   3) 看 report.json 与 samples/ 下的原始 HTML
 *
 * 它只发 GET，不提交任何表单，不改动账号数据。
 */

import { mkdirSync, writeFileSync, existsSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = process.env.CX_OUT || join(HERE, '..', '.workbuddy', 'research');
const SAMPLES = join(OUT, 'samples');

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';
const TIMEOUT = 20000;
const DELAY = 350;

const report = { startedAt: new Date().toISOString(), steps: [] };

function log(icon, msg) {
  console.log(`${icon} ${msg}`);
}
function step(name, detail) {
  report.steps.push({ name, detail, at: new Date().toISOString() });
  log('[STEP]', name + (detail ? ` — ${detail}` : ''));
}
function ok(msg) { log('[ OK ]', msg); }
function bad(msg) { log('[FAIL]', msg); }
function warn(msg) { log('[WARN]', msg); }

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

// ---------- Cookie ----------
const COOKIE_FILE = join(OUT, 'cookie.txt');
let COOKIE = (process.env.CX_COOKIE || '').trim();
if (!COOKIE && existsSync(COOKIE_FILE)) COOKIE = readFileSync(COOKIE_FILE, 'utf8').trim();

function headers(extra = {}) {
  return {
    'User-Agent': UA,
    Cookie: COOKIE,
    'Accept-Language': 'zh-CN,zh;q=0.9',
    Accept: 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8',
    ...extra,
  };
}

async function get(url, extraHeaders = {}, tag = '') {
  const res = await fetch(url, { headers: headers(extraHeaders), redirect: 'follow', signal: AbortSignal.timeout(TIMEOUT) });
  const text = await res.text();
  if (tag) writeFileSync(join(SAMPLES, `${tag}.html`), text, 'utf8');
  return { status: res.status, finalUrl: res.url, text };
}

// ---------- 通用解析 ----------
const FAIL_WORDS = ['无权限', '参数验证不通过', '无效的参数', '您没有权限', '未登录', '请先登录'];

function detectFailure(text) {
  if (!text) return null;
  for (const w of FAIL_WORDS) if (text.includes(w)) return w;
  return null;
}

function attrs(tag) {
  const out = {};
  for (const m of tag.matchAll(/([\w:-]+)\s*=\s*["']([^"']*)["']/g)) out[m[1].toLowerCase()] = m[2];
  return out;
}

function extractHiddenInputs(html) {
  const out = {};
  for (const m of html.matchAll(/<input\b[^>]*>/gi)) {
    const a = attrs(m[0]);
    if (a.id && a.value) out[a.id] = a.value;
  }
  return out;
}

function pickCourses(json) {
  const found = [];
  const push = (d) => {
    const courseId = d.courseid ?? d.courseId;
    const classId = d.clazzid ?? d.classId;
    if (!courseId || !classId) return;
    const name = d.name ?? d.title ?? d.courseName ?? d.coursename ?? '';
    if (!found.some((c) => c.courseId === String(courseId) && c.classId === String(classId))) {
      found.push({ courseId: String(courseId), classId: String(classId), cpi: String(d.cpi ?? ''), name: String(name) });
    }
  };

  // 首选形状：channelList[].content.course.data[]
  try {
    for (const ch of json?.channelList ?? []) {
      const data = ch?.content?.course?.data;
      if (Array.isArray(data)) data.forEach(push);
      else if (data) push(data);
    }
  } catch {}

  // 兜底：任意深度找带 courseid + clazzid 的对象
  if (!found.length) {
    const seen = new Set();
    const walk = (node, depth) => {
      if (!node || depth > 8 || typeof node !== 'object') return;
      if (seen.has(node)) return;
      seen.add(node);
      if (Array.isArray(node)) return node.forEach((n) => walk(n, depth + 1));
      if ((node.courseid ?? node.courseId) && (node.clazzid ?? node.classId)) return push(node);
      for (const k of Object.keys(node)) walk(node[k], depth + 1);
    };
    walk(json, 0);
  }
  return found;
}

function parseWorkRows(html) {
  const rows = [];
  for (const m of html.matchAll(/<li\b[^>]*>/gi)) {
    const a = attrs(m[0]);
    const data = a.data || '';
    if (!/chaoxing\.com/.test(data)) continue;
    const u = safeUrl(data);
    rows.push({
      label: a['aria-label'] || '',
      url: data,
      workId: u?.searchParams.get('workId') || u?.searchParams.get('workid') || '',
      answerId: u?.searchParams.get('answerId') || u?.searchParams.get('answerid') || '',
      enc: u?.searchParams.get('enc') || '',
    });
  }
  return rows;
}

function safeUrl(s) {
  try { return new URL(s.replace(/&amp;/g, '&')); } catch { return null; }
}

// 从作业详情页抽 作答时间 / 得分 / 题量
function parseWorkDetail(html) {
  const text = html.replace(/<[^>]+>/g, ' ').replace(/&nbsp;/g, ' ').replace(/\s+/g, ' ');
  const pick = (re) => (text.match(re) || [])[1]?.trim() || null;
  // 学习通的作答时间只有"月-日 时:分"，没有年份；也兼容带年份的写法
  const DT = '[0-9]{2,4}[-/][0-9]{1,2}(?:[-/][0-9]{1,2})?\\s*[0-9]{1,2}:[0-9]{2}';
  const timeRange = pick(new RegExp(`作答时间[:：]?\\s*(${DT}\\s*至\\s*${DT})`));
  const endAt = timeRange ? (timeRange.split('至')[1] || '').trim() : null;
  return {
    timeRange,
    endAt,
    score: pick(/智能分析\s*([0-9.]+)\s*分/),
    questionCount: pick(/([0-9]+)\s*道题/),
    fullScore: pick(/满分[:：]?\s*([0-9.]+)/),
    rawLength: html.length,
  };
}

const STATUS_MAP = [
  [/未交|未提交|未开始/, 'PENDING'],
  [/待批阅|已交|已提交|待评/, 'SUBMITTED'],
  [/已完成|已批阅|已评分/, 'GRADED'],
  [/已过期|已截止/, 'OVERDUE'],
];
function normalizeStatus(label) {
  for (const [re, s] of STATUS_MAP) if (re.test(label)) return s;
  return 'UNKNOWN';
}

// ---------- 主流程 ----------
async function main() {
  if (!COOKIE) {
    bad(`没有 Cookie。请把浏览器里登录后的 Cookie 存到：${COOKIE_FILE}`);
    bad('或设置环境变量 CX_COOKIE。只发 GET，不改动账号数据。');
    process.exit(2);
  }
  const cookieNames = COOKIE.split(';').map((s) => s.split('=')[0].trim()).filter(Boolean);
  const hasUid = cookieNames.some((n) => n === '_uid');
  const hasFid = cookieNames.some((n) => n === 'fid');
  if (!hasUid || !hasFid) warn(`Cookie 里 ${!hasUid ? '缺 _uid ' : ''}${!hasFid ? '缺 fid' : ''}，可能不是登录态`);
  mkdirSync(SAMPLES, { recursive: true });

  console.log('='.repeat(64));
  console.log('学习通作业链路探针');
  console.log(`输出目录：${OUT}`);
  console.log('='.repeat(64));

  // S1 会话
  step('S1 会话校验', 'GET i.chaoxing.com/base');
  let base;
  try {
    base = await get('https://i.chaoxing.com/base', { Referer: 'https://i.chaoxing.com/' }, '01-base');
  } catch (e) {
    bad(`会话请求失败：${e.message}`);
    return finish();
  }
  if (base.status !== 200) { bad(`base 返回 ${base.status}`); return finish(); }
  if (/passport2\.chaoxing\.com\/login/.test(base.finalUrl)) { bad('被重定向到登录页，Cookie 已失效'); return finish(); }
  const fid = (base.text.match(/["']?fid["']?\s*[:=]\s*["']?(\d{3,})/) || [])[1];
  const siteName = (base.text.match(/id="siteName"[^>]*title="([^"]*)"/) || [])[1];
  const acct = (base.text.match(/aria-label="账号[:：]([^"]*)"/) || [])[1];
  if (!fid) warn('未能从 base 页提取 fid（不致命，课程列表走 JSON 源）');
  ok(`会话有效　账号=${acct || '?'}　学校=${siteName || '?'}　fid=${fid || '?'}`);
  report.session = { fid, siteName, account: acct, finalUrl: base.finalUrl };
  await sleep(DELAY);

  // S2 课程列表
  step('S2 课程列表', '优先 JSON 源 backclazzdata');
  let courses = [];
  try {
    const r = await get('https://mooc1-api.chaoxing.com/mycourse/backclazzdata?view=json&rss=1',
      { Referer: 'https://i.chaoxing.com/base', Accept: 'application/json, text/javascript, */*; q=0.01' }, '02-courselist-json');
    if (r.status === 200) {
      try {
        const j = JSON.parse(r.text);
        courses = pickCourses(j);
        if (courses.length) ok(`JSON 源拿到 ${courses.length} 门课`);
        else warn('JSON 源返回成功但解析不出课程，转 HTML 源');
      } catch { warn('JSON 源不是合法 JSON，转 HTML 源'); }
    } else warn(`JSON 源状态 ${r.status}，转 HTML 源`);
  } catch (e) { warn(`JSON 源异常：${e.message}`); }

  if (!courses.length && fid) {
    const r = await get(`https://mooc2-ans.chaoxing.com/mooc2-ans/visit/courselistdata?courseType=1&courseFid=${fid}`,
      { Referer: 'https://i.chaoxing.com/base' }, '02-courselist-html');
    const f = detectFailure(r.text);
    if (f) bad(`HTML 源失败：${f}`);
    else {
      // 从 a 链接里抓 courseid/clazzid/cpi
      for (const m of r.text.matchAll(/href="([^"]*(?:courseid|courseId)=(\d+)[^"]*clazzid=(\d+)[^"]*)"/gi)) {
        const u = safeUrl(m[1].replace(/&amp;/g, '&'));
        if (!u) continue;
        const courseId = u.searchParams.get('courseid') || u.searchParams.get('courseId');
        const classId = u.searchParams.get('clazzid') || u.searchParams.get('classId');
        if (!courseId || !classId) continue;
        if (courses.some((c) => c.courseId === courseId && c.classId === classId)) continue;
        const name = (m[1].match(/title="([^"]*)"/) || [])[1] || '';
        courses.push({ courseId, classId, cpi: u.searchParams.get('cpi') || '', name });
      }
      if (courses.length) ok(`HTML 源拿到 ${courses.length} 门课`);
      else bad('HTML 源也解析不出课程');
    }
  }

  if (!courses.length) { bad('课程列表为空 —— 到此就断了，先解决 S2'); return finish(); }
  report.courses = courses;
  courses.slice(0, 6).forEach((c, i) => log('      ', `${i + 1}. ${c.name || '(无题名)'}  courseId=${c.courseId} classId=${c.classId} cpi=${c.cpi || '?'}`));
  if (courses.length > 6) log('      ', `… 其余 ${courses.length - 6} 门省略`);

  // S3 + S4 逐课：中间页 -> enc 包 -> 作业列表
  step('S3 抓 enc 包', `GET visit/stucoursemiddle，共 ${courses.length} 门课`);
  const results = [];
  for (const c of courses) {
    await sleep(DELAY);
    const midUrl = `https://mooc1.chaoxing.com/visit/stucoursemiddle?courseid=${c.courseId}&clazzid=${c.classId}&cpi=${c.cpi}&ismooc2=1&v=2`;
    let encs = {};
    try {
      const r = await get(midUrl, { Referer: 'https://i.chaoxing.com/base' }, `03-middle-${c.courseId}`);
      const f = detectFailure(r.text);
      if (f) { warn(`${c.name}：中间页返回「${f}」`); }
      encs = extractHiddenInputs(r.text);
    } catch (e) { warn(`${c.name}：中间页请求异常 ${e.message}`); }

    const keys = ['enc', 'openc', 'oldenc', 'workEnc', 'examEnc'];
    const got = keys.filter((k) => encs[k]);
    if (!encs.workEnc) warn(`${c.name}：中间页没抽到 workEnc（拿到 ${got.join(',') || '无'}）`);

    // 参数组合矩阵：先按社区实测的组合，再逐个退化
    const t = Date.now();
    const q = (o) => new URLSearchParams(Object.entries(o).filter(([, v]) => v != null && v !== '')).toString();
    const common = { courseId: c.courseId, classId: c.classId, cpi: c.cpi, ut: 's', t: String(t) };

    const variants = [
      { name: 'V1 完整（enc=workEnc + stuenc + openc + isdisplaytable）',
        params: { ...common, openc: encs.openc, enc: encs.workEnc, stuenc: encs.enc, isdisplaytable: '2' } },
      { name: 'V2 去 openc / isdisplaytable',
        params: { ...common, enc: encs.workEnc, stuenc: encs.enc } },
      { name: 'V3 enc 用页面通用 enc（验证社区说法）',
        params: { ...common, enc: encs.enc, stuenc: encs.enc } },
      { name: 'V4 只留四件套（最保守）',
        params: { ...common, enc: encs.workEnc } },
    ];

    let winner = null;
    for (const v of variants) {
      const url = `https://mooc1.chaoxing.com/mooc2/work/list?${q(v.params)}`;
      let r;
      try {
        r = await get(url, { Referer: midUrl, 'X-Requested-With': 'XMLHttpRequest' }, `04-worklist-${c.courseId}-${v.name.slice(0, 2)}`);
      } catch (e) { warn(`  ${v.name} 请求异常 ${e.message}`); continue; }
      const f = detectFailure(r.text);
      const rows = parseWorkRows(r.text);
      const verdict = f ? `拒绝：${f}` : rows.length ? `成功：${rows.length} 条` : `空结果（长 ${r.text.length}）`;
      log(verdict.startsWith('成功') ? '[ OK ]' : '[ -- ]', `  ${v.name} -> ${verdict}`);
      if (rows.length && !winner) {
        winner = { variant: v.name, rows };
        break;
      }
      await sleep(DELAY);
    }

    const entry = {
      course: c,
      encKeysPresent: got,
      hasWorkEnc: Boolean(encs.workEnc),
      variant: winner?.variant || null,
      works: (winner?.rows || []).map((r) => ({ ...r, status: normalizeStatus(r.label) })),
    };

    // S5 详情：取第一条「未交」的 data 直链
    const target = entry.works.find((w) => w.status === 'PENDING') || entry.works[0];
    if (target?.url) {
      await sleep(DELAY);
      try {
        const d = await get(target.url, { Referer: midUrl }, `05-workdetail-${c.courseId}`);
        entry.detail = parseWorkDetail(d.text);
        log('      ', `  详情：${target.label.slice(0, 30)} → 题量=${entry.detail.questionCount || '?'} 满分=${entry.detail.fullScore || '?'} 时间=${entry.detail.timeRange || '?'}`);
      } catch (e) { warn(`  详情请求异常 ${e.message}`); }
    }
    results.push(entry);
  }

  report.results = results;
  const totalWorks = results.reduce((n, r) => n + r.works.length, 0);
  const pending = results.reduce((n, r) => n + r.works.filter((w) => w.status === 'PENDING').length, 0);

  console.log('-'.repeat(64));
  ok(`作业总条数 ${totalWorks}，其中未交 ${pending}`);
  const noEnc = results.filter((r) => !r.hasWorkEnc).length;
  if (noEnc) warn(`${noEnc} 门课没抽到 workEnc —— 这些课会拿不到作业列表`);
  const noList = results.filter((r) => r.works.length === 0).length;
  if (noList) warn(`${noList} 门课四种参数组合全部落空 —— 需要走 WebView 兜底`);

  finish();
}

function finish() {
  report.finishedAt = new Date().toISOString();
  writeFileSync(join(OUT, 'report.json'), JSON.stringify(report, null, 2), 'utf8');
  console.log('-'.repeat(64));
  console.log(`报告：${join(OUT, 'report.json')}`);
  console.log(`原始样本：${SAMPLES}`);
}

function selftest() {
  let pass = 0, fail = 0;
  const eq = (name, got, want) => {
    const okv = JSON.stringify(got) === JSON.stringify(want);
    if (okv) { pass++; }
    else { fail++; bad(`${name}\n      得到 ${JSON.stringify(got)}\n      期望 ${JSON.stringify(want)}`); }
  };

  // 隐藏 input：两种属性顺序都要能抽到
  const h1 = `<html><input type="hidden" id="enc" value="E1">
    <input type="hidden" id="workEnc" value="W1"><input value="O1" id="openc" type="hidden">
    <input type="hidden" id="oldenc" value=""><input name="x" value="y"></html>`;
  const e = extractHiddenInputs(h1);
  eq('hidden input 抽取', e, { enc: 'E1', workEnc: 'W1', openc: 'O1' });
  eq('workEnc 存在', Boolean(e.workEnc), true);

  // 作业行：data 属性 + aria-label
  const h2 = `<ul>
    <li onclick="goTask(this);" data="https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?courseId=1&amp;classId=2&amp;cpi=3&amp;workId=99&amp;answerId=88&amp;enc=abc" aria-label="实验2 ; 未交"><span>实验2</span></li>
    <li onclick="goTask(this);" data="https://mooc1.chaoxing.com/mooc-ans/mooc2/work/task?workId=77&amp;answerId=66&amp;enc=def" aria-label="实验1 ; 待批阅"></li>
    <li>无关行，没有 data</li>
  </ul>`;
  const rows = parseWorkRows(h2);
  eq('作业行数量', rows.length, 2);
  eq('第一行 workId', rows[0]?.workId, '99');
  eq('第一行 answerId', rows[0]?.answerId, '88');
  eq('第一行 enc', rows[0]?.enc, 'abc');
  eq('第一行 label', rows[0]?.label, '实验2 ; 未交');

  eq('状态 未交', normalizeStatus('实验2 ; 未交'), 'PENDING');
  eq('状态 待批阅', normalizeStatus('实验1 ; 待批阅'), 'SUBMITTED');
  eq('状态 已完成', normalizeStatus('第3章 ; 已完成'), 'GRADED');
  eq('状态 未知', normalizeStatus('随便什么'), 'UNKNOWN');

  // 课程列表：channelList 形状
  const j = { channelList: [{ content: { course: { data: [
    { courseid: 111, clazzid: 222, cpi: 333, name: '高等数学' },
    { courseid: 111, clazzid: 222, cpi: 333, name: '高等数学(重复)' },
    { courseid: 444, clazzid: 555, cpi: 666, name: '大学英语' },
  ] } } }] };
  const cs = pickCourses(j);
  eq('课程数量（去重）', cs.length, 2);
  eq('课程字段', cs[0], { courseId: '111', classId: '222', cpi: '333', name: '高等数学' });

  // 课程列表：意外形状走兜底遍历
  const cs2 = pickCourses({ a: { b: [{ courseId: '9', classId: '8', cpi: '7', title: '兜底课' }] } });
  eq('兜底遍历', cs2.length, 1);

  // 详情页文本抽取
  const d = parseWorkDetail('<div>共 10 道题 满分: 100 作答时间: 10-08 08:00 至 10-15 23:59 智能分析 86 分</div>');
  eq('题量', d.questionCount, '10');
  eq('满分', d.fullScore, '100');
  eq('得分', d.score, '86');
  eq('时间区间命中', Boolean(d.timeRange), true);
  eq('截止时间', d.endAt, '10-15 23:59');
  eq('带年份的时间', Boolean(parseWorkDetail('作答时间: 2026-10-08 08:00 至 2026-10-15 23:59').timeRange), true);

  eq('失败词 无权限', detectFailure('您没有权限访问'), '您没有权限');
  eq('失败词 正常页', detectFailure('<div>作业列表</div>'), null);

  console.log('-'.repeat(64));
  console.log(`自检：通过 ${pass}　失败 ${fail}`);
  process.exit(fail ? 1 : 0);
}

if (process.argv.includes('--selftest')) {
  selftest();
} else {
  main().catch((e) => {
    bad(`未捕获异常：${e.stack || e.message}`);
    finish();
  });
}
