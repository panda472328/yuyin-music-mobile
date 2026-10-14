import type { Song } from '../types'
import { apiData, parseSearch, record, videoToSong } from './bilibili'
import { buildWbiQuery } from './subtitles'
import { classifySongSearchResult, isSearchMode, parseSearchSuggestions, preferredMusicUploaderMids, rankSongSearchResults, requestsDanceVersion, type SearchMode } from '../shared/search-mode'

type Request = (url: string, timeoutMs?: number) => Promise<unknown>
const API = 'https://api.bilibili.com'

/** Optional uploader requests have a bounded lifetime and never hide official results. */
async function preferredSongs(json: Request, keyword: string): Promise<Song[]> {
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const work = async () => {
    try {
      const nav = record(await json(`${API}/x/web-interface/nav`, 3000))
      const keys = record(record(nav?.data)?.wbi_img)
      if (stopped || typeof keys?.img_url !== 'string' || typeof keys.sub_url !== 'string') return []
      let next = 0
      const results: Song[][] = preferredMusicUploaderMids.map(() => [])
      const worker = async () => {
        while (!stopped && next < preferredMusicUploaderMids.length) {
          const index = next++, mid = preferredMusicUploaderMids[index]
          try {
            const query = buildWbiQuery({ mid, keyword, pn: 1, ps: 10, order: 'pubdate', tid: 0 }, keys.img_url as string, keys.sub_url as string)
            const data = apiData(await json(`${API}/x/space/wbi/arc/search?${query}`, 3000))
            const videos = record(data.list)?.vlist
            if (stopped || !Array.isArray(videos)) continue
            results[index] = videos.slice(0, 10).map(raw => {
              const item = record(raw)
              const song = videoToSong(item ? { ...item, duration: item.length ?? item.duration, mid } : null, keyword)
              return song
            }).filter((song): song is Song => Boolean(song && classifySongSearchResult(song, keyword) === 'single' && !requestsDanceVersion(song.title)))
          } catch { /* Optional source failure leaves the official search intact. */ }
        }
      }
      await Promise.all([worker(), worker()])
      return results.flat()
    } catch { return [] }
  }
  try {
    return await Promise.race([work(), new Promise<Song[]>(resolve => { timer = setTimeout(() => { stopped = true; resolve([]) }, 6000) })])
  } finally { stopped = true; clearTimeout(timer) }
}

export function createSearchApi(json: Request, generation: () => number) {
  return {
    async searchSuggestions(query: string): Promise<string[]> {
      const keyword = query.trim()
      if (!keyword) return []
      if (keyword.length > 200) throw new Error('搜索词过长。')
      const revision = generation()
      const payload = await json(`https://s.search.bilibili.com/main/suggest?${new URLSearchParams({ term: keyword })}`, 8000)
      if (revision !== generation()) throw new Error('账号已变化，请重新搜索。')
      const raw = record(payload)
      if (raw?.code !== undefined && raw.code !== 0) throw new Error('搜索联想暂不可用。')
      return parseSearchSuggestions(payload)
    },
    async search(query: string, page = 1, mode: SearchMode = 'song') {
      const keyword = query.trim()
      if (!keyword || keyword.length > 200 || !isSearchMode(mode)) throw new Error('搜索词或搜索模式无效。')
      if (!Number.isSafeInteger(page) || page < 1 || page > 1000) throw new Error('搜索页码无效。')
      const revision = generation()
      const preferred = mode === 'song' && page === 1 && !requestsDanceVersion(keyword) ? preferredSongs(json, keyword) : Promise.resolve([])
      const params = new URLSearchParams({ search_type: 'video', keyword, order: 'totalrank', page: String(page), page_size: '20' })
      const result = parseSearch(await json(`${API}/x/web-interface/search/type?${params}`), keyword, page)
      const additional = await preferred
      if (revision !== generation()) throw new Error('Bilibili 账号已发生变化，请重新搜索。')
      const unique = [...new Map([...result.songs, ...additional].map(song => [song.bvid, song])).values()]
      return { ...result, songs: mode === 'song' ? rankSongSearchResults(unique, keyword) : result.songs }
    },
  }
}
