import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fixture } from './ui-fixture.mjs'

const output = path.resolve('.qa/full-parity-ui')
await fs.mkdir(output, { recursive: true })
const browser = await chromium.launch({ headless: true, channel: 'chrome' })
const context = await browser.newContext({ viewport: { width: 360, height: 800 } })
const page = await context.newPage(), errors = [], checks = []
page.on('pageerror', error => errors.push(error.message))
await page.route('**/src/native/api.ts*', route => route.fulfill({ contentType: 'text/javascript', body: fixture }))
await page.route(/^https?:\/\/(?!127\.0\.0\.1)/, route => route.abort())
const assert = (value, message) => { if (!value) throw new Error(message) }
async function check(name, action) { await action(); checks.push(name) }
const button = name => page.getByRole('button', { name, exact: true })
async function snapshot(name) { assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Overflow: ${name}`); await page.screenshot({ path: path.join(output, `${name}.png`), fullPage: true }) }
try {
  await page.goto(process.env.MOBILE_UI_URL || 'http://127.0.0.1:5173')
  await button('暂不登录，先逛逛').click()
  await page.getByRole('combobox', { name: '搜索歌名或歌手' }).fill('测试歌曲 A')
  await page.getByRole('combobox', { name: '搜索歌名或歌手' }).press('Enter')
  await button('收藏 测试歌曲 A').click()
  await button('取消收藏 测试歌曲 A').waitFor()
  await button('播放 测试歌曲 A').click()
  await button('正在听').click()
  await check('queue removal/clear preserve current audio, favorites and history', async () => {
    await page.getByRole('button', { name: /^播放队列/ }).click()
    await button('测试歌曲 B 更多操作').click()
    await button('从队列移除').click()
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('qa-mobile-library')).queue.length === 1)
    await button('清空队列').click()
    await button('确认清空').click()
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('qa-mobile-library')).queue.length === 0)
    assert(await page.evaluate(() => { const l=JSON.parse(localStorage.getItem('qa-mobile-library'));return l.favorites.length===1&&l.history.length===1 }), 'Queue cleared library')
    await page.getByRole('heading', { name: '测试歌曲 A', exact: true }).waitFor()
    await snapshot('queue-empty')
  })
  await button('设置').click()
  await check('mute restores last nonzero volume after persistence; sleep timer can be selected and cancelled', async () => {
    await button('静音').click(); await button('取消静音').waitFor()
    await button('取消静音').click(); await button('静音').waitFor()
    assert(await page.evaluate(() => JSON.parse(localStorage.getItem('qa-mobile-library')).settings.volume === .7), 'Unmute lost previous volume')
    await page.getByLabel('睡眠定时', { exact: true }).selectOption('30')
    await page.getByText('已设定', { exact: true }).waitFor({ state: 'attached' })
    await page.getByLabel('睡眠定时', { exact: true }).selectOption('0')
  })
  await check('floating styles await save acknowledgment, remain readable and survive reload', async () => {
    await page.getByRole('switch', { name: '显示悬浮歌词' }).click()
    await page.getByLabel('悬浮歌词字体', { exact: true }).selectOption('serif')
    await page.getByLabel('悬浮歌词颜色', { exact: true }).selectOption('mint')
    await page.getByLabel('悬浮歌词字号', { exact: true }).press('ArrowRight')
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('qa-floating')).fontSize===26)
    await page.getByLabel('悬浮歌词透明度', { exact: true }).press('ArrowLeft')
    await page.waitForFunction(() => JSON.parse(localStorage.getItem('qa-floating')).opacity===.9)
    await button('锁定位置').click(); await button('解锁位置').waitFor()
    await page.evaluate(() => { window.__mobileQA.failSave=true })
    await page.getByLabel('悬浮歌词字体', { exact: true }).selectOption('rounded')
    await page.getByRole('alert').filter({ hasText: '测试保存失败' }).waitFor()
    assert(await page.getByLabel('悬浮歌词字体', { exact: true }).inputValue()==='serif','Unsaved font appeared')
    await page.evaluate(() => { window.__mobileQA.failSave=false }); await button('关闭错误提示').click()
    await page.reload(); await button('暂不登录，先逛逛').click(); await button('设置').click()
    assert(await page.getByLabel('悬浮歌词字体', { exact: true }).inputValue()==='serif','Floating style lost after reload')
  })
  await check('export/preview/merge restore includes preferences and floating styles, failure is atomic in UI', async () => {
    await page.locator('.theme-settings').getByRole('button', { name: /^我的世界/ }).click()
    await button('导出备份').click(); await page.waitForFunction(() => !!window.__mobileQA.backup)
    await page.evaluate(() => { window.__mobileQA.importData=window.__mobileQA.backup })
    await page.getByRole('button', { name: /^经典 · 草木/ }).click()
    await button('导入并合并').click(); await page.getByRole('heading', { name: '合并音乐库备份' }).waitFor()
    await page.getByRole('checkbox').check(); await snapshot('backup-preview')
    await page.evaluate(() => { window.__mobileQA.failSave=true }); await button('确认合并').click()
    await page.locator('.sheet').getByRole('alert').waitFor()
    assert(await page.evaluate(() => (document.documentElement.dataset.theme??'classic')==='classic'),'Failed import changed theme')
    await page.evaluate(() => { window.__mobileQA.failSave=false }); await button('确认合并').click()
    await page.waitForFunction(() => document.documentElement.dataset.theme==='pixel')
    assert(await page.evaluate(() => JSON.parse(localStorage.getItem('qa-mobile-library')).favorites.length===1),'Merge duplicated or removed favorites')
  })
  await check('new settings and backup controls fit 320/360 px and landscape in all themes', async () => {
    for (const theme of ['经典 · 草木','Bilibili','跟随系统','我的世界']) {
      await page.locator('.theme-settings').getByRole('button', { name: new RegExp(`^${theme}`) }).click()
      for(const viewport of [{width:320,height:740},{width:360,height:800},{width:844,height:390}]) { await page.setViewportSize(viewport); await snapshot(`${theme}-${viewport.width}`) }
    }
  })
  await check('an unreadable library remains blocked until explicit backup confirmation', async () => {
    const backup=await page.evaluate(() => window.__mobileQA.backup)
    await page.evaluate(() => localStorage.setItem('qa-corrupt-library','synthetic-corrupt-record'))
    await page.reload(); await page.getByRole('alert').filter({hasText:'测试歌库不可读'}).waitFor()
    await page.evaluate(data => { window.__mobileQA.importData=data },backup)
    await button('从备份恢复').click(); await page.getByText('当前歌库不可读，将从备份恢复，并保留损坏的原记录。').waitFor()
    assert(await page.evaluate(() => localStorage.getItem('qa-corrupt-library')),'Preview wrote a corrupt store')
    await button('确认合并').click(); await page.waitForFunction(() => !localStorage.getItem('qa-corrupt-library'))
    await button('暂不登录，先逛逛').click(); await button('设置').click(); await button('导出备份').waitFor({state:'visible'})
    assert(await button('导出备份').isEnabled(),'Restored library stayed blocked')
    await page.keyboard.press('Control+k'); await page.getByRole('combobox',{name:'搜索歌名或歌手'}).waitFor()
    await page.waitForFunction(() => document.querySelector('[role="combobox"]') === document.activeElement)
  })
  assert(errors.length===0,errors.join('\n'))
  const result={ok:true,checks,errors,scope:'React with synthetic native facade; actual floating window and sleep verified separately on Android'}
  await fs.writeFile(path.join(output,'evidence.json'),JSON.stringify(result,null,2)); console.log(JSON.stringify(result,null,2))
} catch(error) { await snapshot('failure').catch(()=>{}); throw error }
finally { await context.close(); await browser.close() }
