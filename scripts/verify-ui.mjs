import { chromium } from 'playwright'
import fs from 'node:fs/promises'
import path from 'node:path'

// Fictional UI fixtures are served only through this test's intercepted module route.
// Production sources and builds have no mock toggle, test account, or fixture dependency.
const baseURL = process.env.MOBILE_UI_URL || 'http://127.0.0.1:5173'
const output = path.resolve('.qa/mobile-ui')
await fs.mkdir(output, { recursive: true })
const fixture = `
export const isAndroid = true;
export const getLyricOffset = (prefs,provider,bvid) => prefs.lyricOffsets[provider+':'+bvid] ?? (provider==='bilibili' ? -.25 : 0);
const song = {id:'BV0TEST00001',bvid:'BV0TEST00001',title:'测试歌曲 A',artist:'测试歌手',cover:'',duration:260,playCount:6250000,source:'bilibili',url:'https://www.bilibili.com/video/BV0TEST00001/',searchQuery:'测试歌曲 A'};
const other = {...song,id:'BV0TEST00002',bvid:'BV0TEST00002',title:'测试歌曲 B',duration:320};
const defaultLibrary=()=>({version:1,favorites:[],playlists:[{id:'playlist-default',name:'我的歌单',description:'',createdAt:Date.now(),songs:[]}],history:[],settings:{volume:.7,playMode:'sequence',autoPlayFirst:false},queue:[]});
let status={state:'idle',song:null,currentTime:0,duration:0,volume:.7,error:null};
const statusListeners=new Set(),sessionListeners=new Set(),endedListeners=new Set();
const emit=()=>{for(const cb of statusListeners)cb({...status})};
window.__mobileQA={playCalls:0,seekCalls:[],saveCalls:0,failSave:false,advance:(seconds)=>{status.currentTime=seconds;emit()},ended:()=>{status.state='ended';emit();for(const cb of endedListeners)cb(status.song)}};
const listener=(set,callback)=>{set.add(callback);return Promise.resolve({remove:async()=>set.delete(callback)})};
export const mobile={
checkAccount:async()=>localStorage.getItem('qa-mobile-login')==='1'?{loggedIn:true,account:{mid:1234,username:'测试用户',avatar:''}}:{loggedIn:false,account:null},
login:async()=>{localStorage.setItem('qa-mobile-login','1');for(const cb of sessionListeners)cb()},openSource:async()=>{},
search:async(query,page=1)=>({query,songs:[song,other],page,pageSize:20,total:2,hasMore:false}),
play:async(selected)=>{window.__mobileQA.playCalls++;status={...status,state:'playing',song:selected,currentTime:0,duration:selected.duration};emit();return {...status}},
pause:async()=>{status.state='paused';emit();return {...status}},resume:async()=>{status.state='playing';emit();return {...status}},
seek:async(seconds)=>{window.__mobileQA.seekCalls.push(seconds);status.currentTime=seconds;emit();return {...status}},
setVolume:async(volume)=>{status.volume=volume;emit();return {...status}},getStatus:async()=>({...status}),
getLyrics:async(selected,provider)=>({provider,query:'测试歌曲 A',match:{id:1,trackName:'测试歌曲 A',artistName:'测试歌手',albumName:'测试专辑',duration:260,instrumental:false,syncedLyrics:'[00:02.00]测试歌词第一句\\n[00:07.00]测试歌词第二句\\n[00:12.00]测试歌词第三句\\n[00:17.00]测试歌词第四句',plainLyrics:null},...(provider==='bilibili'?{subtitle:{label:'中文',language:'zh-CN',isAI:true}}:{})}),
loadLibrary:async()=>JSON.parse(localStorage.getItem('qa-mobile-library')||'null')||defaultLibrary(),
saveLibrary:async(state)=>{await new Promise(resolve=>setTimeout(resolve,30));if(window.__mobileQA.failSave)throw new Error('QA: 存储空间不足');window.__mobileQA.saveCalls++;localStorage.setItem('qa-mobile-library',JSON.stringify(state))},
loadPreferences:async()=>JSON.parse(localStorage.getItem('qa-mobile-preferences')||'null')||{version:1,lyricsProvider:'bilibili',lyricOffsets:{}},
savePreferences:async(state)=>{if(window.__mobileQA.failSave)throw new Error('QA: 设置存储失败');localStorage.setItem('qa-mobile-preferences',JSON.stringify(state))},
addStatusListener:cb=>listener(statusListeners,cb),addEndedListener:cb=>listener(endedListeners,cb),addSessionListener:cb=>listener(sessionListeners,cb),
getFavoriteFolders:async()=>({mid:1234,username:'测试用户',account:{mid:1234,name:'测试用户'},folders:[{id:42,title:'手机收藏夹',mediaCount:2,cover:'',description:'',isDefault:false}]}),
getFavoriteSongs:async()=>({folder:{id:42,title:'手机收藏夹',mediaCount:2,cover:'',description:'',isDefault:false},songs:[song,other],skippedCount:0,total:2})
};`
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
  await page.getByRole('button', { name: '登录 Bilibili', exact: true }).click()
  await page.getByRole('heading', { name: '发现音乐', exact: true }).waitFor()
  await page.screenshot({ path: path.join(output, '02-search-home.png'), fullPage: true })
  await check('search is explicit and never autoplays', async () => {
    await page.getByRole('textbox', { name: '搜索歌曲或歌手' }).fill('测试歌曲 A')
    await page.getByRole('textbox', { name: '搜索歌曲或歌手' }).press('Enter')
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
  await assert(!errors.length, `Page errors: ${errors.join('; ')}`)
  const result = { ok:true, scope:'React mobile UI with an isolated native facade fixture; no claim of real Bilibili playback', checks, errors, screenshots:output }
  await fs.writeFile(path.join(output,'evidence.json'),JSON.stringify(result,null,2))
  console.log(JSON.stringify(result,null,2))
} catch(error) {
  await page.screenshot({path:path.join(output,'failure.png'),fullPage:true}).catch(()=>{})
  await fs.writeFile(path.join(output,'evidence.json'),JSON.stringify({ok:false,checks,errors,error:String(error)},null,2))
  throw error
} finally {await browser.close()}
