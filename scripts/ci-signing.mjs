import fs from 'node:fs'
import path from 'node:path'

// Never use the developer's local signing directory from CI.
if (process.env.GITHUB_ACTIONS !== 'true' || !process.env.RUNNER_TEMP) throw new Error('此脚本只在 GitHub Actions 临时 runner 中使用。')
const signingPath = path.join(path.resolve(process.env.RUNNER_TEMP), 'yuyin-release-signing.p12')
if (process.argv.includes('--cleanup')) {
  fs.rmSync(signingPath, { force: true })
} else {
  if (process.argv.length !== 2) throw new Error('签名准备脚本不接受文件路径参数。')
  const encoded = (process.env.ANDROID_KEYSTORE_BASE64 || '').replace(/\s/g, '')
  if (!encoded || encoded.length > 1000000 || !/^[A-Za-z0-9+/]+={0,2}$/.test(encoded) || !process.env.YUYIN_ANDROID_KEY_PASSWORD || !process.env.YUYIN_ANDROID_KEY_ALIAS) {
    throw new Error('请配置 ANDROID_KEYSTORE_BASE64、ANDROID_KEY_PASSWORD、ANDROID_KEY_ALIAS secrets，沿用当前发布签名。')
  }
  const bytes = Buffer.from(encoded, 'base64')
  if (!bytes.length || bytes.toString('base64') !== encoded) throw new Error('签名 secret 的 Base64 内容无效。')
  fs.writeFileSync(signingPath, bytes, { flag: 'wx', mode: 0o600 })
  console.log('已准备 runner 临时签名文件；不会生成新的发布密钥。')
}
