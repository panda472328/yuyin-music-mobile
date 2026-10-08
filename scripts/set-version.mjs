import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const [next, codeText] = process.argv.slice(2)
const current = JSON.parse(fs.readFileSync(path.join(root, 'version.json'), 'utf8'))
const code = Number(codeText)
if (!/^\d+\.\d+\.\d+$/.test(next || '') || !Number.isInteger(code) || code <= current.androidVersionCode) {
  throw new Error('用法：node scripts/set-version.mjs 0.1.1 2；versionCode 必须递增。')
}
for (const file of ['package.json', 'package-lock.json']) {
  const target = path.join(root, file)
  const data = JSON.parse(fs.readFileSync(target, 'utf8'))
  data.version = next
  if (data.packages?.['']) data.packages[''].version = next
  fs.writeFileSync(target, `${JSON.stringify(data, null, 2)}\n`)
}
fs.writeFileSync(path.join(root, 'version.json'), `${JSON.stringify({ ...current, version: next, androidVersionCode: code }, null, 2)}\n`)
console.log(`手机版版本更新为 ${next} (${code})`)
