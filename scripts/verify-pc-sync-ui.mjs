import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fixture } from './ui-fixture.mjs'

const output = path.resolve('.qa/pc-sync-ui')
await fs.mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'chrome' })
const context = await browser.newContext({ viewport: { width: 360, height: 800 }, deviceScaleFactor: 1 })
const page = await context.newPage()
const errors = [], checks = []
page.on('pageerror', error => errors.push(error.message))
await page.route('**/src/native/api.ts*', route => route.fulfill({ status: 200, contentType: 'text/javascript', body: fixture }))
await page.route(/^https?:\/\/(?!127\.0\.0\.1)/, route => route.abort())
const assert = (value, reason) => { if (!value) throw new Error(reason) }
async function check(name, action) { await action(); checks.push(name) }
async function snapshot(name) {
  await page.waitForTimeout(180)
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Horizontal overflow in ${name}`)
  await page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true })
}
const input = () => page.getByRole('combobox', { name: '搜索歌名或歌手' })
try {
  await page.goto(process.env.MOBILE_UI_URL || 'http://127.0.0.1:5173')
  await check('guest entry opens the app; guest comments stay read-only', async () => {
    await page.getByRole('button', { name: '暂不登录，先逛逛' }).click()
    await input().fill('测试歌曲 A'); await input().press('Enter')
    await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).click()
    await page.getByRole('button', { name: '正在听', exact: true }).click()
    await page.getByRole('button', { name: '打开当前视频评论区' }).click()
    await page.getByText('未登录 · 可以浏览评论', { exact: true }).waitFor()
    assert(await page.getByRole('button', { name: '点赞 评论测试用户的评论' }).isDisabled(), 'Guest can like')
    await snapshot('guest-comments')
  })
  await check('search modes preserve exact input, call the selected algorithm and never autoplay', async () => {
    await page.getByRole('button', { name: '发现', exact: true }).click()
    const before = await page.evaluate(() => window.__mobileQA.playCalls)
    await input().fill('  测试歌 原曲  ')
    await page.getByRole('button', { name: '视频搜索', exact: true }).click()
    assert(await page.getByRole('textbox', { name: '搜索 Bilibili 视频' }).inputValue() === '  测试歌 原曲  ', 'Switch erased input')
    await page.waitForFunction(() => window.__mobileQA.searchModes.at(-1) === 'video')
    await page.getByRole('button', { name: '歌名搜索', exact: true }).click()
    await page.waitForFunction(() => window.__mobileQA.searchModes.at(-1) === 'song')
    assert(await page.evaluate(() => window.__mobileQA.playCalls) === before, 'Mode change autoplays')
    await input().focus()
    await page.getByRole('listbox', { name: '歌名搜索联想' }).waitFor()
    await page.getByRole('option').first().click()
    assert(await page.evaluate(() => window.__mobileQA.playCalls) === before, 'Suggestion autoplays')
  })
  await check('late search responses cannot replace a changed mode; Chinese composition does not submit early', async () => {
    const before = await page.evaluate(() => window.__mobileQA.playCalls)
    await page.evaluate(() => { window.__mobileQA.holdNextSearch = true })
    await input().fill('慢返回'); await input().press('Enter')
    await page.waitForFunction(() => typeof window.__mobileQA.releaseStaleSearch === 'function')
    await page.getByRole('button', { name: '视频搜索', exact: true }).click()
    await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).waitFor()
    await page.evaluate(() => window.__mobileQA.releaseStaleSearch())
    await page.waitForTimeout(150)
    assert(await page.getByText('过期搜索结果', { exact: true }).count() === 0, 'Old mode response replaced results')
    assert(await page.getByRole('textbox', { name: '搜索 Bilibili 视频' }).inputValue() === '慢返回', 'Late response erased input')
    await page.getByRole('button', { name: '歌名搜索', exact: true }).click()
    await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).waitFor()
    const count = await page.evaluate(() => window.__mobileQA.searchModes.length)
    await input().dispatchEvent('compositionstart')
    await input().fill('中文输入')
    await input().dispatchEvent('keydown', { key: 'Enter', code: 'Enter', isComposing: true })
    await page.waitForTimeout(300)
    assert(await page.evaluate(() => window.__mobileQA.searchModes.length) === count, 'IME Enter submitted a search')
    await input().dispatchEvent('compositionend')
    assert(await page.evaluate(() => window.__mobileQA.playCalls) === before, 'Race or IME started playback')
  })
  await check('classic, Bilibili, system light/dark and pixel themes persist and fit mobile layouts', async () => {
    await page.getByRole('button', { name: '设置', exact: true }).click()
    for (const [theme, label, scheme] of [['classic', '经典 · 草木', 'light'], ['bilibili', 'Bilibili', 'light'], ['system', '跟随系统', 'light'], ['system', '跟随系统', 'dark'], ['pixel', '我的世界', 'light']]) {
      await page.emulateMedia({ colorScheme: scheme })
      await page.locator('.theme-settings').getByRole('button', { name: new RegExp(`^${label}`) }).click()
      await page.waitForFunction(theme => (document.documentElement.dataset.theme ?? 'classic') === theme, theme)
      const expected = theme === 'system' && scheme === 'dark' ? 'rgb(23, 25, 29)' : theme === 'bilibili' ? 'rgb(247, 248, 250)' : theme === 'pixel' ? 'rgb(198, 198, 198)' : theme === 'system' ? 'rgb(247, 247, 247)' : 'rgb(247, 248, 243)'
      await page.waitForFunction(expected => getComputedStyle(document.documentElement).backgroundColor === expected, expected)
      for (const viewport of [{ width: 320, height: 740 }, { width: 360, height: 800 }, { width: 844, height: 390 }]) {
        await page.setViewportSize(viewport)
        await snapshot(`${theme}-${scheme}-settings-${viewport.width}`)
      }
    }
    await page.reload(); await page.getByRole('button', { name: '暂不登录，先逛逛' }).click(); await page.getByRole('button', { name: '设置', exact: true }).click()
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'pixel')
    await page.evaluate(() => { window.__mobileQA.failSave = true })
    await page.getByRole('button', { name: /^经典 · 草木/ }).click()
    await page.getByRole('alert').filter({ hasText: '设置存储失败' }).waitFor()
    assert(await page.evaluate(() => document.documentElement.dataset.theme) === 'pixel', 'Failed save changes theme')
    await page.evaluate(() => { window.__mobileQA.failSave = false })
    await page.getByRole('button', { name: '关闭错误提示' }).click()
  })
  await check('logged-in comments support explicit likes and public sends; MV entry reuses the source', async () => {
    await page.setViewportSize({ width: 360, height: 800 })
    await page.getByRole('button', { name: '账号菜单', exact: true }).click()
    await page.getByRole('button', { name: '登录 Bilibili', exact: true }).click()
    await page.getByRole('button', { name: '发现', exact: true }).click()
    await input().fill('测试歌曲 A'); await input().press('Enter')
    await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).click()
    await page.getByRole('button', { name: '正在听', exact: true }).click()
    const before = await page.evaluate(() => window.__mobileQA.playCalls)
    await page.getByRole('button', { name: '打开当前视频 MV' }).click()
    assert(await page.evaluate(() => window.__mobileQA.playCalls) === before, 'MV started another source')
    await page.getByRole('button', { name: '打开当前视频评论区' }).click()
    await page.getByRole('button', { name: '点赞 评论测试用户的评论' }).click()
    await page.getByRole('button', { name: '取消点赞 评论测试用户的评论' }).waitFor()
    await page.getByRole('textbox', { name: '聊聊这段声音' }).fill('虚构的用户主动发送测试')
    await page.getByRole('button', { name: '发送到 Bilibili', exact: true }).click()
    await page.getByText('虚构的用户主动发送测试', { exact: true }).waitFor()
    for (const viewport of [{ width: 320, height: 740 }, { width: 360, height: 800 }, { width: 844, height: 390 }]) {
      await page.setViewportSize(viewport); await snapshot(`pixel-comments-${viewport.width}`)
    }
  })
  assert(errors.length === 0, errors.join('\n'))
  const result = { ok: true, scope: 'isolated React UI with fictional native responses; no live Bilibili writes, accounts or playback', checks, errors, output }
  await fs.writeFile(path.join(output, 'evidence.json'), JSON.stringify(result, null, 2))
  console.log(JSON.stringify(result, null, 2))
} catch (error) {
  await page.screenshot({ path: path.join(output, 'failure.png'), fullPage: true }).catch(() => {})
  await fs.writeFile(path.join(output, 'evidence.json'), JSON.stringify({ ok: false, checks, errors, error: String(error) }, null, 2))
  throw error
} finally { await context.close(); await browser.close() }
