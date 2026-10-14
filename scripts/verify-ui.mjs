import { fixture } from './ui-fixture.mjs'
import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'

// Fictional UI fixtures are served only through this test's intercepted module route.
// Production sources and builds have no mock toggle, test account, or fixture dependency.
const baseURL = process.env.MOBILE_UI_URL || 'http://127.0.0.1:5173'
const output = path.resolve('.qa/mobile-ui')
await fs.mkdir(output, { recursive: true })

const browser = await chromium.launch({ headless: true, channel: 'chrome' })
const context = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 })
const page = await context.newPage()
const errors = []
page.on('pageerror', error => errors.push(error.message))
await page.route('**/src/native/api.ts*', route => route.fulfill({ status: 200, contentType: 'text/javascript', body: fixture }))
const checks = []
async function check(name, action) { await action(); checks.push(name) }
async function assert(value, reason) { if (!value) throw new Error(reason) }
async function waitStore(checker) { await page.waitForFunction(checker) }
try {
  await page.goto(baseURL)
  await check('official login gate visible before account access', async () => {
    await page.getByRole('heading', { name: '登录，开始听见喜欢' }).waitFor()
    await page.screenshot({ path: path.join(output, '01-login.png'), fullPage: true })
  })
  await check('login dismissal refreshes identity and removes the gate even without a session event', async () => {
    await page.getByRole('button', { name: '登录 Bilibili', exact: true }).click()
    await page.getByRole('heading', { name: '发现音乐', exact: true }).waitFor()
    await assert(await page.getByRole('heading', { name: '登录，开始听见喜欢' }).count() === 0, 'Successful login left the gate open')
  })
  await page.screenshot({ path: path.join(output, '02-search-home.png'), fullPage: true })
  await check('search is explicit and never autoplays', async () => {
    await page.getByRole('combobox', { name: '搜索歌名或歌手' }).fill('测试歌曲 A')
    await page.getByRole('combobox', { name: '搜索歌名或歌手' }).press('Enter')
    await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).waitFor()
    await assert(await page.evaluate(() => window.__mobileQA.playCalls === 0), 'Searching unexpectedly played audio')
    await page.screenshot({ path: path.join(output, '03-search-result.png'), fullPage: true })
  })
  await check('favorites await native storage acknowledgment', async () => {
    await page.getByRole('button', { name: '收藏 测试歌曲 A', exact: true }).click()
    await page.getByRole('button', { name: '取消收藏 测试歌曲 A', exact: true }).waitFor()
    await assert(await page.evaluate(() => JSON.parse(localStorage.getItem('qa-mobile-library')).favorites.length === 1), 'Favorite was not persisted')
  })
  await check('playlist create and add song persist', async () => {
    await page.getByRole('button', { name: '音乐库', exact: true }).click()
    await page.getByRole('button', { name: '新建歌单', exact: true }).click()
    await page.screenshot({ path: path.join(output, '03a-playlist-sheet.png'), fullPage: true })
    await page.getByRole('textbox', { name: '歌单名称' }).fill('手机测试歌单')
    await page.getByRole('button', { name: '保存歌单', exact: true }).click()
    await page.getByRole('heading', { name: '手机测试歌单', exact: true }).waitFor()
    await page.getByRole('button', { name: '发现', exact: true }).click()
    await page.getByRole('button', { name: '测试歌曲 A 更多操作', exact: true }).click()
    await page.getByRole('button', { name: '加入歌单', exact: true }).click()
    await page.getByRole('button', { name: /手机测试歌单/ }).click()
    await waitStore(() => JSON.parse(localStorage.getItem('qa-mobile-library')).playlists.some(playlist => playlist.name === '手机测试歌单' && playlist.songs.length === 1))
  })
  await check('manual playback creates actual play history and queue', async () => {
    await page.getByRole('button', { name: '播放 测试歌曲 A', exact: true }).click()
    await page.getByRole('button', { name: '打开正在播放与歌词', exact: true }).waitFor()
    await waitStore(() => JSON.parse(localStorage.getItem('qa-mobile-library')).history.length === 1)
    await assert(await page.evaluate(() => window.__mobileQA.playCalls === 1), 'Manual selection did not play exactly once')
    await page.getByRole('button', { name: '打开正在播放与歌词', exact: true }).click()
    await page.getByText('Bilibili · 中文 · AI 识别字幕', { exact: true }).waitFor()
    await page.evaluate(() => window.__mobileQA.advance(7.5))
    await page.locator('.lyric-lines button.active').filter({hasText:'测试歌词第二句'}).waitFor()
    await assert(await page.evaluate(() => document.querySelector('.big-play').getBoundingClientRect().bottom < document.querySelector('.bottom-nav').getBoundingClientRect().top), 'Playback control is obscured by bottom navigation')
    await page.screenshot({ path: path.join(output, '04-synced-lyrics.png'), fullPage: true, animations: 'disabled' })
  })
  await check('lyric calibration persists per song/provider without seek', async () => {
    await page.getByRole('button', { name: '歌词同步设置', exact: true }).click()
    await page.getByRole('button', { name: '听到哪一句，点一下校准', exact: true }).click()
    await page.getByRole('button', { name: '对齐 测试歌词第一句', exact: true }).click()
    await waitStore(() => JSON.parse(localStorage.getItem('qa-mobile-preferences')).lyricOffsets['bilibili:BV0TEST00001'] === 5.5)
    await assert(await page.evaluate(() => window.__mobileQA.seekCalls.length === 0), 'Calibration seeked instead of offsetting')
    await page.getByRole('button', { name: '搜索歌词', exact: true }).click()
    await waitStore(() => JSON.parse(localStorage.getItem('qa-mobile-preferences')).lyricsProvider === 'lrclib')
    await page.getByText('LRCLIB · 测试歌手 · 测试歌曲 A', { exact: true }).waitFor()
    await page.getByRole('button', { name: '跳转到 0:02 测试歌词第一句', exact: true }).click()
    await page.waitForFunction(() => window.__mobileQA.seekCalls.includes(2))
  })
  await check('transport and actual ended events advance the saved queue', async () => {
    await page.getByRole('button', { name: '下一首', exact: true }).click()
    await page.getByRole('heading', { name: '测试歌曲 B', exact: true }).waitFor()
    await page.waitForFunction(() => !document.querySelector('.big-play').disabled)
    await page.getByRole('button', { name: '上一首', exact: true }).click()
    await page.getByRole('heading', { name: '测试歌曲 A', exact: true }).waitFor()
    await page.waitForFunction(() => !document.querySelector('.big-play').disabled)
    await page.evaluate(() => window.__mobileQA.ended())
    await page.getByRole('heading', { name: '测试歌曲 B', exact: true }).waitFor()
    await page.waitForFunction(() => !document.querySelector('.big-play').disabled)
    await page.getByRole('button', { name: '列表循环', exact: true }).click()
    await waitStore(() => JSON.parse(localStorage.getItem('qa-mobile-library')).settings.playMode === 'repeat')
    await assert(await page.evaluate(() => JSON.parse(localStorage.getItem('qa-mobile-library')).history.length === 2), 'Transport did not persist play history')
  })
  await check('Bilibili folder import creates a separate playlist', async () => {
    await page.getByRole('button', { name: '音乐库', exact: true }).click()
    await page.getByRole('button', { name: '我的音乐库', exact: true }).click()
    await page.getByRole('button', { name: /导入 Bilibili 收藏夹/ }).click()
    await page.getByRole('button', { name: /手机收藏夹/ }).click()
    await page.getByRole('heading', { name: '手机收藏夹', exact: true }).waitFor()
    await waitStore(() => JSON.parse(localStorage.getItem('qa-mobile-library')).playlists.some(playlist => playlist.id === 'bilibili-1234-42' && playlist.songs.length === 2))
    await page.screenshot({ path: path.join(output, '05-library-import.png'), fullPage: true })
  })
  await check('native save failure does not publish an unsaved favorite', async () => {
    await page.evaluate(() => { window.__mobileQA.failSave = true })
    await page.getByRole('button', { name: '收藏 测试歌曲 B', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '存储空间不足' }).waitFor()
    await assert(await page.getByRole('button', { name: '收藏 测试歌曲 B', exact: true }).count() === 1, 'Unsaved favorite became visible')
    await page.evaluate(() => { window.__mobileQA.failSave = false })
    await page.getByRole('button', { name: '关闭错误提示' }).click()
  })
  await check('account touch menu and persistent settings survive restart', async () => {
    await page.getByRole('button', { name: '账号菜单', exact: true }).click()
    await page.getByRole('button', { name: '切换 / 管理账号', exact: true }).waitFor()
    await page.screenshot({ path: path.join(output, '06a-account-menu.png'), fullPage: true })
    await page.getByRole('button', { name: '关闭账号菜单', exact: true }).click()
    await page.reload()
    await page.getByRole('button', { name: '设置', exact: true }).click()
    await page.getByRole('button', { name: /搜索歌词.*来自 LRCLIB/ }).waitFor()
    await page.screenshot({ path: path.join(output, '06-settings.png'), fullPage: true })
    await page.getByRole('button', { name: '音乐库', exact: true }).click()
    await page.getByRole('button', { name: /我的收藏.*1 首歌曲/ }).click()
    await page.getByRole('button', { name: '取消收藏 测试歌曲 A', exact: true }).waitFor()
  })
  await check('320 / 360 px and landscape layouts do not overflow horizontally', async () => {
    for (const viewport of [{width:320,height:740},{width:360,height:800},{width:844,height:390}]) {
      await page.setViewportSize(viewport)
      await page.getByRole('button', { name: '发现', exact: true }).click()
      await assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `Horizontal overflow at ${viewport.width}`)
      await page.screenshot({path:path.join(output, `07-layout-${viewport.width}.png`),fullPage:viewport.height>600})
    }
    await page.reload()
    await page.getByRole('heading', { name: '发现音乐', exact: true }).waitFor()
    for (const viewport of [{width:320,height:740},{width:360,height:800},{width:844,height:390}]) {
      await page.setViewportSize(viewport)
      await assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), `Home horizontal overflow at ${viewport.width}`)
      await page.screenshot({path:path.join(output, `08-home-${viewport.width}.png`),fullPage:viewport.height>600})
    }
  })
  await check('updates require consent, cancel and retry independently of music data', async () => {
    await page.setViewportSize({width:360,height:800})
    const before = await page.evaluate(() => ({ library:localStorage.getItem('qa-mobile-library'),preferences:localStorage.getItem('qa-mobile-preferences'),playCalls:window.__mobileQA.playCalls }))
    await page.evaluate(() => window.__mobileQA.offerUpdate())
    await page.getByRole('button', {name:'下载更新',exact:true}).waitFor()
    await page.evaluate(() => window.__mobileQA.staleUpdate())
    await page.getByRole('button', {name:'下载更新',exact:true}).waitFor()
    await assert(await page.evaluate(() => window.__mobileQA.downloadCalls === 0), 'An automatic check downloaded without user consent')
    await page.getByRole('button', {name:'稍后更新',exact:true}).click()
    await assert(await page.getByRole('button', {name:'下载更新',exact:true}).count() === 0, 'Dismissed update remained open')
    await page.getByRole('button', {name:'设置',exact:true}).click()
    await page.getByRole('button', {name:'下载更新',exact:true}).click()
    await page.getByRole('progressbar', {name:'更新下载进度'}).waitFor()
    await page.screenshot({path:path.join(output,'09-update-download.png'),fullPage:true})
    await page.getByRole('button', {name:'取消下载',exact:true}).click()
    await page.getByRole('button', {name:'下载更新',exact:true}).waitFor()
    await page.getByRole('button', {name:'下载更新',exact:true}).click()
    await page.evaluate(() => window.__mobileQA.updateReady())
    await page.getByRole('button', {name:'继续安装',exact:true}).click()
    await page.getByText('请允许安装更新，再返回继续',{exact:true}).waitFor()
    await assert(await page.evaluate(() => window.__mobileQA.installCalls === 1), 'Install did not require an explicit action')
    await page.evaluate(() => window.__mobileQA.updateReady())
    await page.screenshot({path:path.join(output,'10-update-ready-offer.png'),fullPage:true})
    await assert(await page.evaluate(expected => JSON.stringify({ library:localStorage.getItem('qa-mobile-library'),preferences:localStorage.getItem('qa-mobile-preferences'),playCalls:window.__mobileQA.playCalls }) === JSON.stringify(expected),before), 'Updates altered saved music or playback')
    for(const viewport of [{width:320,height:740},{width:360,height:800},{width:844,height:390}]) {
      await page.setViewportSize(viewport)
      await assert(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), 'Update card overflow')
      await page.getByRole('button', {name:'继续安装',exact:true}).click()
      await page.getByText('请允许安装更新，再返回继续',{exact:true}).waitFor()
      await page.evaluate(() => window.__mobileQA.updateReady())
      await page.screenshot({path:path.join(output,`11-update-${viewport.width}.png`),fullPage:viewport.height>600})
    }
  })
  await assert(!errors.length, `Page errors: ${errors.join('; ')}`)
  const result = { ok:true, scope:'React mobile UI with an isolated native facade fixture; no claim of real Bilibili playback', checks, errors, screenshots:output }
  await fs.writeFile(path.join(output,'evidence.json'),JSON.stringify(result,null,2))
  console.log(JSON.stringify(result,null,2))
} catch(error) {
  await page.screenshot({path:path.join(output,'failure.png'),fullPage:true}).catch(()=>{})
  await fs.writeFile(path.join(output,'evidence.json'),JSON.stringify({ok:false,checks,errors,error:String(error)},null,2))
  throw error
} finally {await browser.close()}
