import fs from 'node:fs'
import path from 'node:path'
import os from 'node:os'
import { fileURLToPath } from 'node:url'
import { spawn, execFileSync } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'

const project = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const version = JSON.parse(fs.readFileSync(path.join(project, 'version.json'), 'utf8'))
const pkg = JSON.parse(fs.readFileSync(path.join(project, 'package.json'), 'utf8'))
if (version.version !== pkg.version || !Number.isInteger(version.androidVersionCode) || version.androidVersionCode < 1) {
  throw new Error('version.json 与 package.json 必须一致，Android versionCode 必须为正整数。')
}
const localRoot = path.join(process.env.LOCALAPPDATA || path.join(os.homedir(), '.local', 'share'), 'YuyinMobile')
const toolConfig = path.join(project, '.qa', 'toolchain.json')
const tools = fs.existsSync(toolConfig) ? JSON.parse(fs.readFileSync(toolConfig, 'utf8')) : {}
const javaHome = process.env.JAVA_HOME || tools.javaHome
const sdk = process.env.ANDROID_HOME || process.env.ANDROID_SDK_ROOT || tools.sdk
const javaTool = name => path.join(javaHome || '', 'bin', name + (process.platform === 'win32' ? '.exe' : ''))
if (!javaHome || !fs.existsSync(javaTool('javac'))) throw new Error('需要完整 JDK 21，请设置 JAVA_HOME。')
if (!sdk || !fs.existsSync(path.join(sdk, 'platforms', 'android-36'))) throw new Error('需要 Android SDK 36，请设置 ANDROID_HOME。')

const env = {
  ...process.env, JAVA_HOME: javaHome, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk,
  GRADLE_USER_HOME: process.env.GRADLE_USER_HOME || path.join(localRoot, 'toolchain', 'gradle'),
  PATH: [path.dirname(process.execPath), path.join(javaHome, 'bin'), process.env.PATH].join(path.delimiter)
}
function run(command, args, cwd = project) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { cwd, env, stdio: 'inherit', windowsHide: true,
      shell: process.platform === 'win32' && /\.bat$/i.test(command) })
    child.on('error', reject)
    child.on('exit', code => code === 0 ? resolve() : reject(new Error(`${path.basename(command)} exited ${code}`)))
  })
}

const signingDir = path.join(localRoot, 'signing')
fs.mkdirSync(signingDir, { recursive: true })
const signingFile = path.join(signingDir, 'release-signing.json')
if (!env.YUYIN_ANDROID_KEYSTORE && !fs.existsSync(signingFile)) {
  const keyStore = path.join(signingDir, 'yuyin-mobile-release.p12')
  if (fs.existsSync(keyStore)) throw new Error('已有签名文件但缺少配置，请恢复原配置，避免改变升级签名。')
  env.YUYIN_ANDROID_KEY_PASSWORD = randomBytes(32).toString('hex')
  await run(javaTool('keytool'), ['-genkeypair', '-keystore', keyStore, '-storetype', 'PKCS12',
    '-storepass:env', 'YUYIN_ANDROID_KEY_PASSWORD', '-keypass:env', 'YUYIN_ANDROID_KEY_PASSWORD',
    '-alias', 'yuyin-mobile', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000',
    '-dname', 'CN=Yuyin Mobile, O=Yuyin, C=CN', '-noprompt'])
  fs.writeFileSync(signingFile, JSON.stringify({ keyStore, keyAlias: 'yuyin-mobile', password: env.YUYIN_ANDROID_KEY_PASSWORD }, null, 2), { mode: 0o600 })
}
if (!env.YUYIN_ANDROID_KEYSTORE) {
  const signing = JSON.parse(fs.readFileSync(signingFile, 'utf8'))
  env.YUYIN_ANDROID_KEYSTORE = signing.keyStore
  env.YUYIN_ANDROID_KEY_PASSWORD = signing.password
  env.YUYIN_ANDROID_KEY_ALIAS = signing.keyAlias
}
if (!env.YUYIN_ANDROID_KEY_PASSWORD || !fs.existsSync(env.YUYIN_ANDROID_KEYSTORE)) throw new Error('缺少 release 签名配置。')

await run(process.execPath, ['node_modules/typescript/bin/tsc', '--noEmit'])
await run(process.execPath, ['node_modules/tsx/dist/cli.mjs', '--test', 'tests/mobile.test.ts'])
await run(process.execPath, ['node_modules/vite/bin/vite.js', 'build'])
await run(process.execPath, ['node_modules/@capacitor/cli/bin/capacitor', 'sync', 'android'])
fs.writeFileSync(path.join(project, 'android', 'local.properties'), `sdk.dir=${sdk.replaceAll('\\', '/')}\n`)
await run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', ['--no-daemon', 'testDebugUnitTest', 'assembleRelease', 'assembleDebug', 'assembleDebugAndroidTest'], path.join(project, 'android'))

const artifactDir = path.join(project, 'artifacts')
fs.mkdirSync(artifactDir, { recursive: true })
const filename = `Yuyin-Mobile-${version.version}.apk`
const apk = path.join(artifactDir, filename)
fs.copyFileSync(path.join(project, 'android', 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'), apk)
const hash = createHash('sha256').update(fs.readFileSync(apk)).digest('hex')
fs.writeFileSync(`${apk}.sha256`, `${hash}  ${filename}\n`)
let commit = null
try { commit = execFileSync(process.env.YUYIN_GIT || 'git', ['rev-parse', 'HEAD'], { cwd: project, encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'] }).trim() } catch {}
fs.writeFileSync(path.join(artifactDir, 'release.json'), JSON.stringify({ ...version, applicationId: 'com.yuyin.music.mobile', filename, sha256: hash, bytes: fs.statSync(apk).size, builtAt: new Date().toISOString(), commit }, null, 2))
console.log(`APK ready: ${apk}`)
console.log(`SHA256: ${hash}`)
