#!/usr/bin/env node
/**
 * 一键发版：生成 update/latest.json + 建 GitHub Release 并传 APK。
 *
 * 用法（先跑完 assembleRelease）：
 *
 *   node tools/release-update.mjs "作业状态修正" "内置更新" "升级清一次缓存"
 *
 * 每条命令行参数就是**一条**更新日志，故意要求写成短语：
 * 内置更新的弹窗只显示三条，写完一整段话在手机上根本读不完。
 *
 * 做的事：
 *   1. 从 app/build.gradle.kts 读 versionCode / versionName（唯一来源，不手抄）
 *   2. 算 APK 的 sizeBytes 与 sha256
 *   3. 写 update/latest.json
 *   4. 有 gh CLI 就顺手建 Release 并上传 APK
 *
 * 不做的事：不自动 git commit / push——发版是要人确认的事，
 * 脚本最后只把该执行的两条命令打出来。
 */

import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { readFileSync, writeFileSync, statSync, existsSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const gradlePath = join(root, 'app', 'build.gradle.kts')
const apkPath = join(root, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk')
const manifestPath = join(root, 'update', 'latest.json')

/** 单条日志的字数上限。和软件内弹窗的 shortNotes(maxChars) 对齐 */
const NOTE_MAX = 20

function fail(msg) {
  console.error(`✗ ${msg}`)
  process.exit(1)
}

// ---------- 1. 版本号 ----------
const gradle = readFileSync(gradlePath, 'utf8')
const versionCode = Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1] ?? 0)
const versionName = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1] ?? ''
if (!versionCode || !versionName) fail('app/build.gradle.kts 里没读到 versionCode/versionName')

// 仓库名以代码里的常量为准，避免脚本和 App 各写一份
const sourceKt = readFileSync(
  join(root, 'app/src/main/java/com/xxt/study/core/update/UpdateSource.kt'),
  'utf8',
)
const repo = sourceKt.match(/REPO\s*=\s*"([^"]+)"/)?.[1] ?? ''
if (!repo) fail('UpdateSource.kt 里没读到 REPO')

// ---------- 2. 更新日志（强制简写） ----------
const args = process.argv.slice(2)
// --no-release：只写 latest.json，不建 Release（第一次配仓库、或者想先本地验证时用）
const skipRelease = args.includes('--no-release')
const notes = args.filter((s) => !s.startsWith('--')).map((s) => s.trim()).filter(Boolean)
if (notes.length === 0) fail('至少给一条更新日志，例如：node tools/release-update.mjs "作业状态修正"')
if (notes.length > 3) fail(`最多三条：弹窗放不下第四条（现在 ${notes.length} 条）`)
for (const n of notes) {
  if (n.length > NOTE_MAX) fail(`「${n}」有 ${n.length} 字，超过 ${NOTE_MAX} 字，请写成短语`)
}

// ---------- 3. APK ----------
if (!existsSync(apkPath)) fail(`找不到 ${apkPath}，先跑 :app:assembleRelease`)
const apk = readFileSync(apkPath)
const sizeBytes = statSync(apkPath).size
const sha256 = createHash('sha256').update(apk).digest('hex')

// ---------- 4. latest.json ----------
const manifest = {
  versionCode,
  versionName,
  sizeBytes,
  sha256,
  notes,
  packageName: 'com.xxt.study',
  url: `https://github.com/${repo}/releases/download/v${versionName}/app-release.apk`,
}

// 目录可能还不存在（第一次发版）
mkdirSync(dirname(manifestPath), { recursive: true })
writeFileSync(manifestPath, JSON.stringify(manifest, null, 2) + '\n', 'utf8')

console.log(`✓ update/latest.json 已更新`)
console.log(`  versionCode ${versionCode} / v${versionName}`)
console.log(`  ${(sizeBytes / 1048576).toFixed(2)} MB  sha256 ${sha256.slice(0, 16)}…`)
console.log(`  notes: ${notes.join(' | ')}`)

// ---------- 5. GitHub Release（可选） ----------
let ghOk = true
try {
  execFileSync('gh', ['--version'], { stdio: 'ignore' })
} catch {
  ghOk = false
}

const tag = `v${versionName}`
if (skipRelease) {
  console.log('\n（--no-release：跳过 Release 创建，只更新了 latest.json）')
} else if (ghOk) {
  console.log(`\n正在创建 Release ${tag} …`)
  try {
    execFileSync(
      'gh',
      [
        'release', 'create', tag, apkPath,
        '--repo', repo,
        '--title', tag,
        // 发布页也用简写：和弹窗同一份文案，避免两处说法不一致
        '--notes', notes.map((n) => `- ${n}`).join('\n'),
      ],
      { stdio: 'inherit' },
    )
    console.log(`✓ Release ${tag} 已发布`)
  } catch {
    console.error(`✗ Release 创建失败（可能同名 tag 已存在），latest.json 已经写好，可重试上传`)
  }
} else {
  console.log('\n（没装 gh CLI，跳过 Release 创建）手动执行：')
  console.log(`  gh release create ${tag} "${apkPath}" --repo ${repo} --title ${tag} --notes "- ${notes.join('\\n- ')}"`)
}

console.log('\n接下来记得把清单提交上去（App 靠它判断有没有新版）：')
console.log('  git add update/latest.json && git commit -m "release: v' + versionName + '"')
console.log('  git push')
