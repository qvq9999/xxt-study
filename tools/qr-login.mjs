#!/usr/bin/env node
/**
 * 扫码登录 + 取 Cookie（电脑端验证用）
 *
 * 做法：用 CDP 驱动本机 Edge 打开学习通登录页 → 截二维码 → 轮询直到登录成功 →
 *       把 chaoxing 域的 Cookie 写到 .workbuddy/research/cookie.txt
 *
 * 为什么要这一步：手机上做不了端到端验证，先把「登录 → 课程 → enc 包 → 作业列表 → 详情」
 * 这条链在电脑上跑通，再出 APK，避免拿着没验证过的假设去打安装包。
 *
 * 前置：Edge 已用 --remote-debugging-port 启动（见 tools/pc-verify.sh）
 * 用法：node tools/qr-login.mjs
 */

import { writeFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const OUT = process.env.CX_OUT || join(HERE, '..', '.workbuddy', 'research');
const CDP = process.env.CDP || 'http://127.0.0.1:9222';
const LOGIN_URL = 'https://passport2.chaoxing.com/login';
const POLL_MS = 2500;
const MAX_MS = 8 * 60 * 1000;

mkdirSync(OUT, { recursive: true });

const QR_FILE = join(OUT, 'qr.png');
const COOKIE_FILE = join(OUT, 'cookie.txt');

function log(...a) {
  console.log(`[qr]`, ...a);
}

async function pickTarget() {
  for (let i = 0; i < 30; i++) {
    try {
      const list = await (await fetch(`${CDP}/json/list`)).json();
      const page = list.find((t) => t.type === 'page' && t.webSocketDebuggerUrl);
      if (page) return page;
    } catch {}
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`连不上 CDP（${CDP}）。先用 tools/pc-verify.sh 启动 Edge。`);
}

class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url);
    this.id = 0;
    this.waiting = new Map();
    this.ready = new Promise((res, rej) => {
      this.ws.addEventListener('open', () => res());
      this.ws.addEventListener('error', (e) => rej(new Error(`WebSocket 错误：${e.message || e}`)));
    });
    this.ws.addEventListener('message', (ev) => {
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      if (msg.id && this.waiting.has(msg.id)) {
        const { resolve, reject } = this.waiting.get(msg.id);
        this.waiting.delete(msg.id);
        msg.error ? reject(new Error(msg.error.message)) : resolve(msg.result);
      }
    });
  }

  async send(method, params = {}) {
    await this.ready;
    const id = ++this.id;
    return new Promise((resolve, reject) => {
      this.waiting.set(id, { resolve, reject });
      this.ws.send(JSON.stringify({ id, method, params }));
      setTimeout(() => {
        if (this.waiting.has(id)) {
          this.waiting.delete(id);
          reject(new Error(`${method} 超时`));
        }
      }, 20000);
    });
  }

  close() {
    try { this.ws.close(); } catch {}
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function shot(cdp, file) {
  const { data } = await cdp.send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: false });
  writeFileSync(file, Buffer.from(data, 'base64'));
}

function chaoxingCookies(all) {
  return all.filter((c) => /(^|\.)chaoxing\.com$/.test(c.domain) || c.domain.endsWith('chaoxing.com'));
}

function cookieHeader(list) {
  return list.map((c) => `${c.name}=${c.value}`).join('; ');
}

async function main() {
  const target = await pickTarget();
  const cdp = new Cdp(target.webSocketDebuggerUrl);
  await cdp.send('Page.enable');
  await cdp.send('Network.enable');

  log('打开登录页');
  await cdp.send('Page.navigate', { url: LOGIN_URL });
  await sleep(4000);

  const { result } = await cdp.send('Runtime.evaluate', {
    expression: 'document.title + " | " + location.href',
    returnByValue: true,
  });
  log('当前页面：', result?.value);

  await shot(cdp, QR_FILE);
  log('二维码已截图 →', QR_FILE);

  const started = Date.now();
  let lastShot = Date.now();
  let lastCount = -1;

  while (Date.now() - started < MAX_MS) {
    await sleep(POLL_MS);

    let all = [];
    try {
      ({ cookies: all } = await cdp.send('Network.getAllCookies'));
    } catch (e) {
      log('取 Cookie 失败：', e.message);
      continue;
    }

    const cx = chaoxingCookies(all);
    const uid = cx.find((c) => c.name === '_uid' && c.value);
    if (cx.length !== lastCount) {
      log(`chaoxing Cookie ${cx.length} 个${uid ? '（含 _uid）' : ''}`);
      lastCount = cx.length;
    }

    if (uid) {
      const header = cookieHeader(cx);
      writeFileSync(COOKIE_FILE, header, 'utf8');
      const names = cx.map((c) => c.name).sort();
      log('登录成功，已写入', COOKIE_FILE);
      log('拿到 Cookie：', names.join(', '));
      const key = ['_uid', 'fid', 'uf', '_d', 'vc3'].map((n) => `${n}=${names.includes(n) ? '有' : '缺'}`).join('  ');
      log(key);
      cdp.close();
      process.exit(0);
    }

    // 二维码会过期，定期重截一张，保证用户任何时候扫到的都是新的
    if (Date.now() - lastShot > 25000) {
      lastShot = Date.now();
      await shot(cdp, QR_FILE).catch(() => {});
      log('二维码已刷新截图');
    }
  }

  log('超时未检测到登录');
  cdp.close();
  process.exit(3);
}

main().catch((e) => {
  console.error('[qr] 失败：', e.message);
  process.exit(1);
});
