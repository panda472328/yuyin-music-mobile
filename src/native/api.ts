import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core'
import type { YuyinNativePlugin, MobilePreferences } from './contract'
import type { BilibiliFavoriteFoldersResult, BilibiliFavoriteSongsResult, Song, PlaybackStatus } from '../types'
import type { LyricsLookupResult, LyricsProvider, LyricsTrack } from '../lyric-types'
import { BilibiliAccountSession, BilibiliError } from '../domain/account'
import { apiData, parseFolder, parseSearch, positiveInteger, record, videoToSong } from '../domain/bilibili'
import { initializeLibrary, saveLibrary as normalizeLibrary, type LibraryState } from '../domain/library'
import { defaultPreferences, normalizePreferences } from '../domain/preferences'
import { buildLyricsQueries, selectLyricsMatch } from '../domain/lyrics'
import { buildWbiQuery, selectSubtitle, subtitleDescriptor, subtitleBodyToLyrics } from '../domain/subtitles'
export { getLyricOffset } from '../domain/preferences'

const API = 'https://api.bilibili.com'
const plugin = registerPlugin<YuyinNativePlugin>('YuyinMobile')
export const isAndroid = Capacitor.getPlatform() === 'android'
const noopListener = (): PluginListenerHandle => ({ remove: async () => {} })
const unavailable = (): never => { throw new Error('登录和播放需要在 Android APK 中使用，浏览器只用于预览界面。') }

const preview: YuyinNativePlugin = {
  request: async () => unavailable(), openLogin: async () => unavailable(), openSource: async () => unavailable(),
  play: async () => unavailable(), pause: async () => unavailable(), resume: async () => unavailable(), seek: async () => unavailable(), setVolume: async () => unavailable(),
  getStatus: async () => ({ state: 'idle', song: null, currentTime: 0, duration: 0, volume: 0.7, error: null }),
  readStore: async ({ key }) => ({ data: localStorage.getItem(`yuyin-mobile-${key}-v1`) }),
  writeStore: async ({ key, data }) => { localStorage.setItem(`yuyin-mobile-${key}-v1`, data); return { saved: true } },
  addListener: async () => noopListener(),
}

function parseLyricsTrack(value: unknown): LyricsTrack | null {
  const track = record(value)
  const string = (input: unknown, limit: number): input is string => typeof input === 'string' && input.length <= limit
  const lyrics = (input: unknown): input is string | null => input === null || string(input, 100000)
  if (!track || !positiveInteger(track.id) || !string(track.trackName, 500) || !track.trackName.trim()
    || !string(track.artistName, 500) || !(track.albumName === null || string(track.albumName, 500))
    || typeof track.duration !== 'number' || !Number.isFinite(track.duration) || track.duration < 0 || track.duration > 86400
    || typeof track.instrumental !== 'boolean' || !lyrics(track.syncedLyrics) || !lyrics(track.plainLyrics)) return null
  return { id: track.id, trackName: track.trackName, artistName: track.artistName, albumName: track.albumName ?? '', duration: track.duration, instrumental: track.instrumental, syncedLyrics: track.syncedLyrics, plainLyrics: track.plainLyrics }
}

/** Native cookies stay inside Android. This facade never reads or returns session secrets. */
export function createMobileApi(native: YuyinNativePlugin, android = true) {
  const account = new BilibiliAccountSession(() => json(`${API}/x/web-interface/nav`))
  const cache = new Map<string, { result: LyricsLookupResult; expires: number }>()
  const pendingLyrics = new Map<string, Promise<LyricsLookupResult>>()
  let sessionGeneration = 0

  function invalidateSession(): void {
    account.invalidate(); sessionGeneration++; cache.clear(); pendingLyrics.clear()
  }

  async function json(url: string): Promise<unknown> {
    const response = await native.request({ url })
    if (response.status < 200 || response.status >= 300) {
      const restricted = [401, 403, 412, 429].includes(response.status)
      throw new BilibiliError(restricted ? '服务要求登录或验证，请打开源站后重试。' : `网络服务返回 HTTP ${response.status}。`, restricted ? 'VERIFICATION_REQUIRED' : 'HTTP_ERROR', restricted)
    }
    if (typeof response.body !== 'string' || response.body.length > 2 * 1024 * 1024) throw new Error('网络响应过大或格式异常。')
    try { return JSON.parse(response.body) as unknown }
    catch { throw new BilibiliError('服务返回了异常页面，请完成登录或验证后重试。', 'INVALID_RESPONSE', true) }
  }

  async function readLibrary(): Promise<LibraryState> {
    const { data } = await native.readStore({ key: 'library' })
    const result = initializeLibrary({ getItem: () => data, setItem: () => {} })
    if (result.error) throw new Error(result.error)
    return result.state
  }

  async function writeLibrary(state: LibraryState): Promise<void> {
    let data = ''
    const result = normalizeLibrary(state, { getItem: () => null, setItem: (_key, value) => { data = value } })
    if (!result.ok) throw new Error(result.error)
    const saved = await native.writeStore({ key: 'library', data })
    if (!saved.saved) throw new Error('音乐库保存失败，原记录已保留，请检查手机存储空间。')
  }

  async function lrclib(song: Song): Promise<LyricsLookupResult> {
    const request = { song, query: song.searchQuery }
    const tracks = new Map<number, LyricsTrack>()
    let lastQuery = ''
    for (const query of buildLyricsQueries(request)) {
      lastQuery = query
      const payload = await json(`https://lrclib.net/api/search?${new URLSearchParams({ q: query })}`)
      if (!Array.isArray(payload) || payload.length > 500) throw new Error('歌词服务返回的数据格式异常。')
      for (const raw of payload) { const track = parseLyricsTrack(raw); if (track) tracks.set(track.id, track) }
      if (payload.length && !tracks.size) throw new Error('歌词服务返回的数据格式异常。')
      const match = selectLyricsMatch(request, [...tracks.values()])
      if (match) return { provider: 'lrclib', query, match }
    }
    return { provider: 'lrclib', query: lastQuery, match: null, message: '暂未找到相符的歌词，可切换 Bilibili 字幕。' }
  }

  async function bilibiliLyrics(song: Song): Promise<LyricsLookupResult> {
    const view = apiData(await json(`${API}/x/web-interface/view?bvid=${song.bvid}`))
    const firstPage = Array.isArray(view.pages) ? record(view.pages[0]) : null
    const cid = firstPage?.cid ?? view.cid
    if (!positiveInteger(cid) || !positiveInteger(view.aid)) throw new Error('Bilibili 视频分段信息异常。')
    const nav = record(await json(`${API}/x/web-interface/nav`))
    if (nav?.code !== 0 && nav?.code !== -101) apiData(nav)
    const images = record(record(nav?.data)?.wbi_img)
    if (typeof images?.img_url !== 'string' || typeof images.sub_url !== 'string') throw new Error('Bilibili 字幕签名信息异常。')
    const query = buildWbiQuery({ bvid: song.bvid, cid, aid: view.aid }, images.img_url, images.sub_url)
    const player = apiData(await json(`${API}/x/player/wbi/v2?${query}`))
    const candidates = record(player.subtitle)?.subtitles
    if (!Array.isArray(candidates) || candidates.length > 100) throw new Error('Bilibili 字幕列表格式异常。')
    const selected = selectSubtitle(candidates.map(subtitleDescriptor).filter(item => item !== null))
    if (!selected) {
      if (player.need_login_subtitle === true) return { provider: 'bilibili', query: '', match: null, requiresLogin: true, message: '该视频要求登录后读取字幕，请打开 Bilibili 登录后重试。' }
      if (candidates.length) throw new Error('该视频字幕地址暂不支持。')
      return { provider: 'bilibili', query: '', match: null, message: 'Bilibili 暂未提供该视频的可用字幕，可切换搜索歌词。' }
    }
    const lyrics = subtitleBodyToLyrics(await json(selected.url))
    const subtitle = { language: selected.language, label: selected.label, isAI: selected.isAI }
    if (!lyrics.plainLyrics) return { provider: 'bilibili', query: '', match: null, subtitle, message: '该视频字幕没有可显示的文字。' }
    const duration = typeof firstPage?.duration === 'number' ? firstPage.duration : song.duration || lyrics.duration
    return { provider: 'bilibili', query: '', subtitle, match: { id: cid, trackName: song.title, artistName: '', albumName: '', duration, instrumental: false, syncedLyrics: lyrics.syncedLyrics, plainLyrics: lyrics.plainLyrics } }
  }

  return {
    checkAccount: () => android ? account.getStatus() : Promise.resolve({ loggedIn: false as const, account: null }),
    async login() {
      invalidateSession()
      try { await native.openLogin() }
      finally { invalidateSession() }
    },
    openSource: () => native.openSource(),
    async search(query: string, page = 1) {
      const keyword = query.trim()
      if (!keyword || keyword.length > 200) throw new Error('请输入 1 到 200 个字符的歌名或歌手。')
      if (!Number.isSafeInteger(page) || page < 1 || page > 1000) throw new Error('搜索页码无效。')
      const permit = await account.requireLoggedIn()
      const params = new URLSearchParams({ search_type: 'video', keyword, order: 'totalrank', page: String(page), page_size: '20' })
      const result = parseSearch(await json(`${API}/x/web-interface/search/type?${params}`), keyword, page)
      account.assertRevision(permit.revision)
      return result
    },
    async play(song: Song) { const permit = await account.requireLoggedIn(); account.assertRevision(permit.revision); return native.play({ song }) },
    pause: () => native.pause(), resume: () => native.resume(), seek: (seconds: number) => native.seek({ seconds }),
    setVolume: (volume: number) => native.setVolume({ volume }), getStatus: () => native.getStatus(),
    async getLyrics(song: Song, provider: LyricsProvider = 'bilibili', force = false): Promise<LyricsLookupResult> {
      if (!song || song.source !== 'bilibili' || !/^BV[0-9A-Za-z]{10}$/.test(song.bvid) || typeof song.title !== 'string' || !song.title.trim() || song.title.length > 1000 || !Number.isFinite(song.duration) || song.duration < 0 || song.duration > 86400) throw new Error('歌曲信息异常，无法查询歌词。')
      if (provider !== 'bilibili' && provider !== 'lrclib') throw new Error('歌词来源无效。')
      const key = JSON.stringify([provider, song.bvid, song.title, song.duration, song.searchQuery ?? '', sessionGeneration])
      const stored = cache.get(key)
      if (!force && stored && stored.expires > Date.now()) return stored.result
      const existing = pendingLyrics.get(key)
      if (existing && !force) return existing
      const generation = sessionGeneration
      const pending = (provider === 'bilibili' ? bilibiliLyrics(song) : lrclib(song)).then(result => {
        if (generation !== sessionGeneration) throw new Error('Bilibili 账号已变化，请重新查询歌词。')
        if (pendingLyrics.get(key) === pending && !result.requiresLogin) {
          cache.set(key, { result, expires: Date.now() + (result.match ? 21600000 : 60000) })
          if (cache.size > 100) cache.delete(cache.keys().next().value as string)
        }
        return result
      }).finally(() => { if (pendingLyrics.get(key) === pending) pendingLyrics.delete(key) })
      pendingLyrics.set(key, pending)
      return pending
    },
    loadLibrary: readLibrary, saveLibrary: writeLibrary,
    async loadPreferences(): Promise<MobilePreferences> {
      const { data } = await native.readStore({ key: 'preferences' })
      if (data === null) return defaultPreferences()
      if (data.length > 256000) throw new Error('设置记录过大，原记录已保留。')
      try { return normalizePreferences(JSON.parse(data)) }
      catch { throw new Error('读取设置失败，原记录已保留，请检查备份。') }
    },
    async savePreferences(preferences: MobilePreferences): Promise<void> {
      const data = JSON.stringify(normalizePreferences(preferences))
      if (!(await native.writeStore({ key: 'preferences', data })).saved) throw new Error('设置保存失败，请检查手机存储空间。')
    },
    addStatusListener: (callback: (status: PlaybackStatus) => void) => native.addListener('status', callback),
    addEndedListener: (callback: (song: Song) => void) => native.addListener('ended', event => callback(event.song)),
    addSessionListener: (callback: () => void) => native.addListener('sessionChanged', () => {
      invalidateSession(); callback()
    }),
    async getFavoriteFolders(): Promise<BilibiliFavoriteFoldersResult> {
      const permit = await account.requireLoggedIn()
      const data = apiData(await json(`${API}/x/v3/fav/folder/created/list-all?${new URLSearchParams({ up_mid: String(permit.account.mid), web_location: '333.1387' })}`))
      if (!Array.isArray(data.list)) throw new Error('Bilibili 收藏夹列表格式异常。')
      const folders = data.list.slice(0, 100).map(item => parseFolder(item)).filter(folder => folder !== null)
      account.assertRevision(permit.revision)
      return { mid: permit.account.mid, username: permit.account.username, account: { mid: permit.account.mid, name: permit.account.username }, folders }
    },
    async getFavoriteSongs(folderId: number): Promise<BilibiliFavoriteSongsResult> {
      if (!positiveInteger(folderId)) throw new Error('收藏夹参数无效。')
      const permit = await account.requireLoggedIn()
      let folder = null
      const songs = new Map<string, Song>()
      let total = 0
      for (let page = 1; page <= 75; page++) {
        const data = apiData(await json(`${API}/x/v3/fav/resource/list?${new URLSearchParams({ media_id: String(folderId), pn: String(page), ps: '40', platform: 'web', web_location: '333.1387' })}`))
        if (!Array.isArray(data.medias) || data.medias.length > 40) throw new Error('Bilibili 收藏夹内容格式异常。')
        if (page === 1) { folder = parseFolder(data.info, folderId); total = folder?.mediaCount ?? 0 }
        if (!folder || total > 3000) throw new Error(total > 3000 ? '收藏夹超过 3000 首，请缩小收藏夹后导入。' : '收藏夹信息异常。')
        for (const raw of data.medias) { const song = videoToSong(raw); if (song) songs.set(song.bvid, song) }
        account.assertRevision(permit.revision)
        if (page * 40 >= total || data.medias.length === 0) break
      }
      if (!folder) throw new Error('收藏夹信息异常。')
      return { folder, songs: [...songs.values()], skippedCount: Math.max(0, total - songs.size), total }
    },
  }
}

export const mobile = createMobileApi(isAndroid ? plugin : preview, isAndroid)
export type MobileApi = ReturnType<typeof createMobileApi>
