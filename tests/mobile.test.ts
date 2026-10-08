import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import test from 'node:test'
import type { PluginListenerHandle } from '@capacitor/core'
import { createMobileApi } from '../src/native/api'
import type { YuyinNativePlugin } from '../src/native/contract'
import type { Song, PlaybackStatus } from '../src/types'
import { md5 } from '../src/domain/md5'
import { activeLyricIndex, parseLrc } from '../src/domain/lyrics'
import { buildWbiQuery, safeSubtitleUrl, subtitleBodyToLyrics } from '../src/domain/subtitles'
import { defaultPreferences, getLyricOffset, normalizePreferences } from '../src/domain/preferences'
import { createPlaylist, toggleFavorite, importBilibiliPlaylist, addToPlaylist, recordHistory } from '../src/domain/library'

const song: Song = { id: 'BV1234567890', bvid: 'BV1234567890', title: '周杰伦 - 晴天', artist: '音乐 UP', duration: 270, playCount: 10, cover: '', url: 'https://www.bilibili.com/video/BV1234567890/', source: 'bilibili', searchQuery: '周杰伦 晴天' }
const idle: PlaybackStatus = { state: 'idle', song: null, currentTime: 0, duration: 0, volume: 0.7, error: null }
const nav = { code: 0, data: { isLogin: true, mid: 10, uname: '手机测试用户', face: '//i0.hdslb.com/avatar.jpg', wbi_img: { img_url: 'https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png', sub_url: 'https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70ac45.png' } } }
type RequestHandler = (url: URL) => unknown | Promise<unknown>

function harness(handler: RequestHandler = () => nav) {
  const stored = new Map<string, string>()
  const requests: URL[] = []
  const events = new Map<string, (event: any) => void>()
  let plays = 0
  let acknowledged = true
  const native = {
    request: async ({ url }: { url: string }) => { const parsed = new URL(url); requests.push(parsed); return { status: 200, body: JSON.stringify(await handler(parsed)) } },
    readStore: async ({ key }: { key: string }) => ({ data: stored.get(key) ?? null }),
    writeStore: async ({ key, data }: { key: string; data: string }) => { if (acknowledged) stored.set(key, data); return { saved: acknowledged } },
    openLogin: async () => {}, openSource: async () => {},
    play: async ({ song: playing }: { song: Song }) => { plays++; return { ...idle, state: 'playing' as const, song: playing } },
    pause: async () => ({ ...idle, state: 'paused' as const }), resume: async () => ({ ...idle, state: 'playing' as const }),
    seek: async ({ seconds }: { seconds: number }) => ({ ...idle, currentTime: seconds }), setVolume: async ({ volume }: { volume: number }) => ({ ...idle, volume }), getStatus: async () => idle,
    addListener: async (event: string, callback: (value: any) => void): Promise<PluginListenerHandle> => { events.set(event, callback); return { remove: async () => { events.delete(event) } } },
  } as YuyinNativePlugin
  return { api: createMobileApi(native), native, stored, requests, events, plays: () => plays, failWrites: () => { acknowledged = false } }
}

test('browser-compatible WBI MD5 matches UTF-8 reference, padding boundaries and long strings', () => {
  for (const text of ['', 'a', 'abc', 'message digest', '晴天🎵', 'a'.repeat(55), 'a'.repeat(56), 'a'.repeat(64), '雨'.repeat(1000)]) assert.equal(md5(text), createHash('md5').update(text).digest('hex'))
  const query = buildWbiQuery({ foo: "a!'()*中" }, nav.data.wbi_img.img_url, nav.data.wbi_img.sub_url, 1702204169)
  assert.match(query, /^foo=a%E4%B8%AD&wts=1702204169&w_rid=[a-f0-9]{32}$/)
})

test('official search rank remains intact, labels decoded, and searching never invokes playback', async () => {
  const h = harness(url => url.pathname.endsWith('/nav') ? nav : { code: 0, data: { result: [
    { bvid: song.bvid, title: '<em>晴天</em> &amp; 周杰伦', author: '低播放量', pic: '//i0.hdslb.com/a.jpg', duration: '04:30', play: 10 },
    { bvid: 'BV0987654321', title: '第二条', author: '高播放量', duration: '3:20', play: '100万' },
    { bvid: 'invalid', title: '无效视频' },
  ], numResults: 21, numPages: 2, pagesize: 20 } })
  const result = await h.api.search(' 周杰伦 晴天 ')
  assert.equal(h.requests[1].searchParams.get('order'), 'totalrank')
  assert.equal(result.songs[0].bvid, song.bvid)
  assert.equal(result.songs[0].title, '晴天 & 周杰伦')
  assert.equal(result.songs[0].duration, 270)
  assert.equal(result.songs[1].playCount, 1000000)
  assert.equal(result.hasMore, true)
  assert.equal(h.plays(), 0)
})

test('logged-out and challenged sessions cannot turn into successful search/play', async () => {
  const anonymous = harness(() => ({ code: -101, data: { isLogin: false } }))
  assert.equal((await anonymous.api.checkAccount()).loggedIn, false)
  await assert.rejects(anonymous.api.search('晴天'), /登录/)
  await assert.rejects(anonymous.api.play(song), /登录/)
  assert.equal(anonymous.plays(), 0)
  const challenge = harness(() => ({ code: -352, message: 'challenge' }))
  await assert.rejects(challenge.api.checkAccount(), /验证/)
})

test('session change rejects an in-flight search and listener cleanup removes bridge callback', async () => {
  let release!: (value: unknown) => void
  const h = harness(url => url.pathname.endsWith('/nav') ? nav : new Promise(resolve => { release = resolve }))
  let changed = false
  const listener = await h.api.addSessionListener(() => { changed = true })
  const result = h.api.search('晴天')
  while (!release) await new Promise(resolve => setTimeout(resolve, 0))
  h.events.get('sessionChanged')?.({})
  release({ code: 0, data: { result: [] } })
  await assert.rejects(result, /账号已发生变化/)
  assert.equal(changed, true)
  await listener.remove()
  assert.equal(h.events.has('sessionChanged'), false)
})

test('returning from login invalidates old checks and play permits without a native session event', async () => {
  let signedIn = true
  const h = harness(() => signedIn ? nav : { code: -101, data: { isLogin: false } })
  await h.api.checkAccount()
  h.native.openLogin = async () => { signedIn = false }
  await h.api.login()
  await assert.rejects(h.api.play(song), /登录/)
  assert.equal(h.plays(), 0)
  h.native.openLogin = async () => { signedIn = true }
  await h.api.login()
  assert.equal((await h.api.checkAccount()).loggedIn, true)
})

test('favorites, playlists, queue, history and manual-play preferences survive an acknowledged store round trip', async () => {
  const h = harness()
  let state = await h.api.loadLibrary()
  state = toggleFavorite(state, song)
  state = createPlaylist(state, '手机歌单')
  const playlist = state.playlists.at(-1)!
  state = addToPlaylist(state, playlist.id, song)
  state = recordHistory({ ...state, queue: [song], settings: { volume: 0.35, playMode: 'shuffle', autoPlayFirst: true } }, song, 100)
  await h.api.saveLibrary(state)
  const reopened = await createMobileApi(h.native).loadLibrary()
  assert.equal(reopened.favorites[0].bvid, song.bvid)
  assert.equal(reopened.playlists.at(-1)?.songs[0].bvid, song.bvid)
  assert.equal(reopened.history[0].playedAt, 100)
  assert.equal(reopened.queue[0].bvid, song.bvid)
  assert.deepEqual(reopened.settings, { volume: 0.35, playMode: 'shuffle', autoPlayFirst: false })
  const old = h.stored.get('library')
  h.failWrites()
  await assert.rejects(h.api.saveLibrary({ ...reopened, favorites: [] }), /保存失败/)
  assert.equal(h.stored.get('library'), old)
})

test('corrupt stored data is reported and preserved instead of replaced by defaults', async () => {
  const h = harness()
  for (const raw of ['{', '{}', JSON.stringify({ version: 2, favorites: [], playlists: [], history: [], queue: [] })]) {
    h.stored.set('library', raw)
    await assert.rejects(h.api.loadLibrary(), /原记录已保留/)
    assert.equal(h.stored.get('library'), raw)
  }
  h.stored.set('preferences', '{"version":9}')
  await assert.rejects(h.api.loadPreferences(), /原记录已保留/)
})

test('Bilibili subtitles use original video timing, end gaps, and AI/UP source metadata', async () => {
  const subtitleUrl = 'https://aisubtitle.hdslb.com/bfs/ai_subtitle/test.json'
  const h = harness(url => {
    if (url.pathname.endsWith('/nav')) return nav
    if (url.pathname.endsWith('/view')) return { code: 0, data: { aid: 2, cid: 3, pages: [{ cid: 3, duration: 270 }] } }
    if (url.pathname.endsWith('/v2')) { assert.match(url.searchParams.get('w_rid') ?? '', /^[a-f0-9]{32}$/); return { code: 0, data: { subtitle: { subtitles: [{ lan: 'ai-zh', lan_doc: '中文（自动生成）', subtitle_url: subtitleUrl }] } } } }
    return { body: [{ from: 12.25, to: 14, content: '故事的小黄花' }, { from: 15, to: 18, content: '从出生那年就飘着' }] }
  })
  const result = await h.api.getLyrics(song, 'bilibili')
  assert.equal(result.subtitle?.isAI, true)
  const lines = parseLrc(result.match?.syncedLyrics ?? '')
  assert.deepEqual(lines.map(line => line.time), [12.25, 14, 15, 18])
  assert.equal(lines[activeLyricIndex(lines, 14.5)].text, '')
  assert.equal(lines[activeLyricIndex(lines, 12, -0.25)].text, '故事的小黄花')
  assert.equal(h.requests.at(-1)?.href, subtitleUrl)
  const count = h.requests.length
  await h.api.getLyrics(song, 'bilibili')
  assert.equal(h.requests.length, count)
})

test('unavailable Bilibili captions stay absent and unsupported external subtitle URLs are refused', async () => {
  const handler = (captions: unknown[]) => (url: URL) => url.pathname.endsWith('/nav') ? nav : url.pathname.endsWith('/view') ? { code: 0, data: { aid: 2, cid: 3 } } : { code: 0, data: { subtitle: { subtitles: captions } } }
  assert.equal((await harness(handler([])).api.getLyrics(song, 'bilibili')).match, null)
  const h = harness(handler([{ lan: 'zh', lan_doc: '中文', subtitle_url: 'https://attacker.example/subtitle.json' }]))
  await assert.rejects(h.api.getLyrics(song, 'bilibili'), /字幕地址/)
  assert.equal(h.requests.some(url => url.hostname === 'attacker.example'), false)
  for (const unsafe of ['https://i0.hdslb.com.attacker.example/bfs/subtitle/a', 'http://i0.hdslb.com/bfs/subtitle/a', 'https://user:password@i0.hdslb.com/bfs/subtitle/a', 'https://i0.hdslb.com/bfs/other/a']) assert.equal(safeSubtitleUrl(unsafe), null)
  assert.throws(() => subtitleBodyToLyrics({ body: [{ from: -1, to: 2, content: '无效' }] }))
})

test('LRCLIB finds the same song and singer and does not treat UP author as song artist', async () => {
  const base = { albumName: '叶惠美', duration: 270, instrumental: false, syncedLyrics: '[00:12.25]故事的小黄花', plainLyrics: '故事的小黄花' }
  const h = harness(() => [ { ...base, id: 1, trackName: '晴天的你', artistName: '周杰伦' }, { ...base, id: 2, trackName: '晴天', artistName: '另一位歌手' }, { ...base, id: 3, trackName: '晴天', artistName: '周杰伦' } ])
  const result = await h.api.getLyrics(song, 'lrclib')
  assert.equal(result.match?.id, 3)
  assert.equal(h.requests[0].hostname, 'lrclib.net')
  assert.ok(h.requests[0].searchParams.get('q')?.includes('晴天'))
})

test('lyric offsets are independently persisted for provider/video and corrupt keys cannot escape bounds', async () => {
  const h = harness()
  const preferences = await h.api.loadPreferences()
  assert.equal(preferences.lyricsProvider, 'bilibili')
  assert.equal(getLyricOffset(preferences, 'bilibili', song.bvid), -0.25)
  assert.equal(getLyricOffset(preferences, 'lrclib', song.bvid), 0)
  preferences.lyricsProvider = 'lrclib'
  preferences.lyricOffsets[`lrclib:${song.bvid}`] = 2.25
  await h.api.savePreferences(preferences)
  const reopened = await createMobileApi(h.native).loadPreferences()
  assert.equal(getLyricOffset(reopened, 'lrclib', song.bvid), 2.25)
  assert.equal(getLyricOffset(reopened, 'bilibili', song.bvid), -0.25)
  const sanitized = normalizePreferences({ ...defaultPreferences(), lyricOffsets: { [`bilibili:${song.bvid}`]: 999, other: 4 } })
  assert.deepEqual(sanitized.lyricOffsets, { [`bilibili:${song.bvid}`]: 120 })
})

test('favorite folder import paginates, removes duplicate videos, and merges later imports into one playlist', async () => {
  const h = harness(url => {
    if (url.pathname.endsWith('/nav')) return nav
    if (url.pathname.endsWith('/list-all')) return { code: 0, data: { list: [{ id: 90, title: '我的收藏', media_count: 41 }] } }
    return { code: 0, data: { info: { id: 90, title: '我的收藏', media_count: 41 }, medias: [{ bvid: song.bvid, title: song.title, upper: { name: song.artist }, duration: 270 }, ...(url.searchParams.get('pn') === '2' ? [{ bvid: 'BV0987654321', title: '第二首', duration: 230 }] : [])] } }
  })
  const folders = await h.api.getFavoriteFolders()
  assert.equal(folders.mid, 10)
  const imported = await h.api.getFavoriteSongs(90)
  assert.equal(imported.songs.length, 2)
  assert.equal(imported.skippedCount, 39)
  const library = await h.api.loadLibrary()
  const input = { accountMid: folders.mid, folderId: 90, name: imported.folder.title, songs: imported.songs }
  const first = importBilibiliPlaylist(library, input)
  const second = importBilibiliPlaylist(first.state, input)
  assert.equal(second.state.playlists.filter(playlist => playlist.id === first.playlistId).length, 1)
  assert.equal(second.state.playlists.at(-1)?.songs.length, 2)
})
