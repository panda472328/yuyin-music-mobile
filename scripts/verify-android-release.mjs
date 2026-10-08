import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'
import { releaseConfig } from './release-utils.mjs'

const root = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const pkg = JSON.parse(fs.readFileSync(path.join(root, 'package.json'), 'utf8'))
const config = releaseConfig(root, `android-v${pkg.version}`)
const artifact = path.join(root, 'artifacts', config.filename)
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT
if (!sdk || !fs.existsSync(artifact)) throw new Error('需要 ANDROID_HOME 和已构建的正式 APK。')
const buildTools = path.join(sdk, 'build-tools', '36.0.0')
const aapt = path.join(buildTools, process.platform === 'win32' ? 'aapt.exe' : 'aapt')
// aapt on Windows cannot reliably decode Chinese absolute artifact paths.
// Keep its argument relative and let the OS set the Unicode working directory.
const artifactArgument = path.relative(root, artifact)
const badging = execFileSync(aapt, ['dump', 'badging', artifactArgument], { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
const packageLine = badging.split(/\r?\n/).find(line => line.startsWith('package: ')) || ''
const value = name => packageLine.match(new RegExp(`\\b${name}='([^']*)'`))?.[1]
if (value('name') !== 'com.yuyin.music.mobile' || value('versionName') !== config.version || Number(value('versionCode')) !== config.versionCode) throw new Error('APK 的应用 ID、versionName 或 versionCode 与发布源码不一致。')
const java = process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin', process.platform === 'win32' ? 'java.exe' : 'java') : 'java'
const verification = execFileSync(java, ['-jar', path.join(buildTools, 'lib', 'apksigner.jar'), 'verify', '--verbose', '--print-certs', artifactArgument], { cwd: root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] })
const certificateDigests = [...verification.matchAll(/^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$/gim)].map(match => match[1].toLowerCase())
// Public certificate fingerprint from android-v0.1.2, not a private signing secret.
const expected = '068675cef3bd1e3402408efa3ddf0a26f2827b460945d8780072460cdfc78146'
if (certificateDigests.length !== 1 || certificateDigests[0] !== expected) throw new Error('APK 签名与现有正式版本不同，禁止发布不可覆盖升级的文件。')
console.log(`正式 APK 版本与升级签名校验通过：${config.version} (${config.versionCode})。`)
