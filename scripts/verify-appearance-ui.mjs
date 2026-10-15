import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fixture } from './ui-fixture.mjs'

const output = path.resolve('.qa/appearance-ui')
await fs.mkdir(output, { recursive: true })
const browser = await chromium.launch({ channel: 'chrome', headless: true })
const context = await browser.newContext({ viewport: { width: 360, height: 800 } })
const page = await context.newPage()
const errors = [], checks = []
const assert = (value, reason) => { if (!value) throw new Error(reason) }
page.on('pageerror', error => errors.push(error.message))
await page.route('**/src/native/api.ts*', route => route.fulfill({ status: 200, contentType: 'text/javascript', body: fixture }))
await page.route(/^https?:\/\/(?!127\.0\.0\.1)/, route => route.abort())
const settings = () => page.getByRole('button', { name: '设置', exact: true }).click()
const player = () => page.getByRole('button', { name: '正在听', exact: true }).click()
const picker = label => page.getByRole('group', { name: label, exact: true })
async function snapshot(name) {
  await page.evaluate(() => document.fonts.ready)
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${name}: horizontal overflow`)
  await page.screenshot({ path: path.join(output, `${name}.png`) })
}
try {
  await page.goto(process.env.MOBILE_UI_URL || 'http://127.0.0.1:5173')
  await page.getByRole('button', { name: '暂不登录，先逛逛' }).click()
  const search = page.getByRole('combobox', { name: '搜索歌名或歌手' })
  await search.fill('测试歌曲 A'); await search.press('Enter')
  assert(await page.evaluate(() => window.__mobileQA.playCalls) === 0, 'Search autoplays')
  await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).click()
  await player()
  await page.getByRole('button', { name: '暂停', exact: true }).waitFor()
  await page.locator('.lyric-lines > button').first().waitFor()
  await page.evaluate(() => window.__mobileQA.advance(8))
  await page.locator('.seek-track').getByText('0:08', { exact: true }).waitFor()
  for (const [theme, label, scheme] of [['classic', '经典 · 草木', 'light'], ['bilibili', 'Bilibili', 'light'], ['system', '跟随系统', 'light'], ['system', '跟随系统', 'dark'], ['pixel', '我的世界', 'light']]) {
    await page.emulateMedia({ colorScheme: scheme })
    await settings()
    await picker('选择界面主题').getByRole('button', { name: new RegExp(`^${label}`) }).click()
    await page.waitForFunction(theme => (document.documentElement.dataset.theme || 'classic') === theme, theme)
    for (const [style, name] of [['classic', '经典'], ['focus', '聚光'], ['paper', '手札'], ['minecraft', '我的世界']]) {
      await picker('播放页歌词样式').getByRole('button', { name: new RegExp(`^${name}`) }).click()
      await player()
      await page.locator(`.lyric-window[data-lyric-style="${style}"]`).waitFor()
      assert(await page.locator('.lyric-lines > button.active').textContent() === '测试歌词第二句', 'Style changed lyric timing')
      assert(await page.locator('.lyric-lines > button.active').evaluate(el => getComputedStyle(el).backgroundColor) === 'rgba(0, 0, 0, 0)', 'Lyrics gained a background')
      await page.waitForFunction(() => { const row=document.querySelector('.lyric-lines > button.active')?.getBoundingClientRect(), area=document.querySelector('.lyric-window')?.getBoundingClientRect(); return row && area && row.top >= area.top && row.bottom <= area.bottom })
      await snapshot(`${theme}-${scheme}-${style}`)
      await settings()
    }
    await page.getByRole('button', { name: '发现', exact: true }).click()
    await search.fill(''); await snapshot(`${theme}-${scheme}-home`)
  }
  checks.push('four independent lyric styles across classic, Bilibili, system light/dark and Minecraft; transparent lyrics retain the active timestamp')
  await page.reload(); await page.getByRole('button', { name: '暂不登录，先逛逛' }).click()
  await snapshot('minecraft-home-inventory')
  await settings()
  const floating = picker('悬浮歌词样式')
  await page.getByRole('combobox', { name: '悬浮歌词字体' }).selectOption('serif')
  await floating.getByRole('button', { name: /^我的世界/ }).click()
  assert(await page.getByRole('combobox', { name: '悬浮歌词字体' }).isDisabled(), 'Pixel font is editable')
  assert(await page.getByRole('combobox', { name: '悬浮歌词字体' }).inputValue() === 'serif', 'Pixel style lost the saved font')
  await floating.getByRole('button', { name: /^手札/ }).click()
  assert(await page.getByRole('combobox', { name: '悬浮歌词字体' }).isEnabled(), 'Returning style does not restore font controls')
  await page.reload(); await page.getByRole('button', { name: '暂不登录，先逛逛' }).click(); await settings()
  await picker('播放页歌词样式').getByRole('button', { name: /^我的世界/ }).and(page.locator('[aria-pressed="true"]')).waitFor()
  await picker('悬浮歌词样式').getByRole('button', { name: /^手札/ }).and(page.locator('[aria-pressed="true"]')).waitFor()
  assert(await page.getByRole('combobox', { name: '悬浮歌词字体' }).inputValue() === 'serif', 'Reload lost saved font')
  await page.evaluate(() => { window.__mobileQA.failSave = true })
  await picker('播放页歌词样式').getByRole('button', { name: /^聚光/ }).click()
  await page.getByRole('alert').filter({ hasText: '设置存储失败' }).waitFor()
  assert(await picker('播放页歌词样式').getByRole('button', { name: /^我的世界/ }).getAttribute('aria-pressed') === 'true', 'Failed save changed style')
  await page.evaluate(() => { window.__mobileQA.failSave = false })
  await page.getByRole('button', { name: '关闭错误提示' }).click()
  checks.push('main/floating style and font choices persist independently; failed saves retain the confirmed style')
  await page.getByRole('button', { name: '发现', exact: true }).click()
  await search.fill('测试歌曲 A'); await search.press('Enter')
  await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).click()
  await player()
  await page.getByRole('button', { name: '暂停', exact: true }).waitFor()
  await page.locator('.lyric-lines > button').first().waitFor()
  await page.evaluate(() => window.__mobileQA.advance(8))
  await page.locator('.seek-track').getByText('0:08', { exact: true }).waitFor()
  await settings()
  for (const viewport of [{ width: 320, height: 740 }, { width: 360, height: 800 }, { width: 844, height: 390 }]) {
    await page.setViewportSize(viewport)
    await picker('播放页歌词样式').scrollIntoViewIfNeeded(); await snapshot(`settings-${viewport.width}`)
    for (const button of await picker('播放页歌词样式').getByRole('button').all()) {
      const box = await button.boundingBox(); assert(box.width >= 44 && box.height >= 44, 'Small style touch target')
    }
    await player(); await snapshot(`player-${viewport.width}`)
    await page.getByRole('combobox', { name: '播放页歌词样式' }).selectOption('paper')
    await page.getByRole('button', { name: '歌词同步设置', exact: true }).click()
    await snapshot(`calibration-${viewport.width}`)
    await page.getByRole('button', { name: '歌词同步设置', exact: true }).click()
    await settings()
  }
  assert(await page.evaluate(() => window.__mobileQA.seekCalls.length) === 0, 'Appearance sought media')
  assert(await page.evaluate(() => window.__mobileQA.playCalls) === 1, 'Reload or style selection restarted playback')
  checks.push('320/360 px and landscape settings, player and calibration fit; appearance changes do not seek or restart media')
  assert(errors.length === 0, errors.join('; '))
  const evidence = { ok: true, scope: 'Isolated React UI with controlled native facade, not a real Android playback test', checks, errors }
  await fs.writeFile(path.join(output, 'evidence.json'), JSON.stringify(evidence, null, 2))
  console.log(JSON.stringify(evidence, null, 2))
} catch (error) {
  await snapshot('failure').catch(() => {})
  await fs.writeFile(path.join(output, 'evidence.json'), JSON.stringify({ ok: false, checks, errors, error: String(error) }, null, 2))
  throw error
} finally { await browser.close() }
