import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Search, Library, Music2, Settings2, Play, Pause, Heart, Plus, ChevronRight, ChevronLeft,
  SkipBack, SkipForward, ListMusic, MoreHorizontal, X, Check, Repeat1, Shuffle, Repeat,
  Volume2, VolumeX, Download, Upload, Timer, LogIn, RefreshCw, FolderHeart, Trash2, Pencil, Clock3, Radio, ExternalLink,
  MessageCircle, MonitorPlay, AudioLines, ArrowRight, SlidersHorizontal, LoaderCircle, Info } from 'lucide-react'
import type { BilibiliAccountStatus, BilibiliFavoriteFolder, PlaybackStatus, SearchResult, Song } from './types'
import type { LyricsLookupResult, LyricsProvider } from './lyric-types'
import { addToPlaylist, createPlaylist, deletePlaylist, getNextIndex, importBilibiliPlaylist,
  recordHistory, removeFromQueue, mergeLibraryBackup, removeFromPlaylist, renamePlaylist, toggleFavorite, type LibraryState } from './domain/library'
import { createBackup, parseBackup, type MobileBackup } from './domain/backup'
import FloatingLyricsSettings from './components/FloatingLyricsSettings'
import ThemeSettings from './components/ThemeSettings'
import ThemeArtwork from './ThemeArtwork'
import LyricStylePicker from './LyricStylePicker'
import { LYRIC_STYLE_OPTIONS, type LyricStyle } from './shared/lyric-styles'
import { activeLyricIndex, parseLrc } from './domain/lyrics'
import { defaultPreferences } from './domain/preferences'
import { AccountGateController } from './domain/account-gate'
import { mobile, isAndroid, getLyricOffset } from './native/api'
import SearchBox from './SearchBox'
import CommentsView from './CommentsView'
import { rankSongSearchResults, type SearchMode } from './shared/search-mode'
import { applyTheme } from './theme'
import type { MobilePreferences } from './native/contract'
import packageInfo from '../package.json'
import { UpdateCard, useMobileUpdates } from './components/Updates'

type Tab = 'search' | 'library' | 'player' | 'settings' | 'comments'
type LibraryView = 'overview' | 'favorites' | 'history' | string
type Dialog = { type: 'create' } | { type: 'rename'; id: string; name: string } |
  { type: 'delete'; id: string; name: string } | { type: 'add'; song: Song } |
  { type: 'song'; song: Song; playlistId?: string; queue?: boolean } | { type: 'folders' } | { type: 'clearQueue' } | { type: 'backup'; backup: MobileBackup }
const idle: PlaybackStatus = { state: 'idle', song: null, currentTime: 0, duration: 0, volume: 0.7, error: null }
const loggedOut: BilibiliAccountStatus = { loggedIn: false, account: null }
const modeLabel = { sequence: '列表循环', repeat: '单曲循环', shuffle: '随机播放' }
const time = (seconds: number) => `${Math.floor(Math.max(0, seconds) / 60)}:${String(Math.floor(Math.max(0, seconds) % 60)).padStart(2, '0')}`
const plays = (value: number) => value >= 100000000 ? `${(value / 100000000).toFixed(1)}亿` : value >= 10000 ? `${(value / 10000).toFixed(1)}万` : String(value)
const message = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试。'

function Cover({ song, className = '' }: { song?: Song | null; className?: string }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [song?.cover])
  return <span className={`cover ${className}`}>
    {song?.cover && !failed ? <img src={song.cover} alt="" referrerPolicy="no-referrer" onError={() => setFailed(true)} /> : <AudioLines aria-hidden="true" />}
  </span>
}

function Avatar({ url, label = '' }: { url?: string; label?: string }) {
  const [failed, setFailed] = useState(false)
  useEffect(() => setFailed(false), [url])
  return url && !failed ? <img src={url} alt={label} referrerPolicy="no-referrer" onError={() => setFailed(true)} /> : <Music2 size={20} />
}

function SongRow({ song, favorite, active, onPlay, onFavorite, onMore, index }: {
  song: Song; favorite: boolean; active: boolean; onPlay: () => void; onFavorite: () => void; onMore: () => void; index?: number
}) {
  return <div className={`song-row ${active ? 'is-current' : ''}`}>
    <button className="song-main" onClick={onPlay} aria-label={`播放 ${song.title}`}>
      {index !== undefined && <span className="song-index">{active ? <AudioLines size={15} /> : String(index + 1).padStart(2, '0')}</span>}
      <Cover song={song} />
      <span className="song-copy"><strong>{song.title}</strong><span>{song.artist} <i>·</i> {time(song.duration)}{index === undefined && <> <i>·</i> {plays(song.playCount)}播放</>}</span></span>
    </button>
    <button className={`icon-button favorite-button ${favorite ? 'is-favorite' : ''}`} onClick={onFavorite} aria-label={favorite ? `取消收藏 ${song.title}` : `收藏 ${song.title}`} aria-pressed={favorite}><Heart size={18} fill={favorite ? 'currentColor' : 'none'} /></button>
    <button className="icon-button" onClick={onMore} aria-label={`${song.title} 更多操作`}><MoreHorizontal size={20} /></button>
  </div>
}

export default function App() {
  const updates = useMobileUpdates()
  const [tab, setTab] = useState<Tab>('search')
  const [libraryView, setLibraryView] = useState<LibraryView>('overview')
  const [library, setLibrary] = useState<LibraryState | null>(null)
  const libraryRef = useRef<LibraryState | null>(null)
  const [preferences, setPreferences] = useState<MobilePreferences | null>(null)
  const preferencesRef = useRef<MobilePreferences | null>(null)
  const storesReady = useRef(false)
  const libraryWrites = useRef<Promise<unknown>>(Promise.resolve())
  const preferenceWrites = useRef<Promise<unknown>>(Promise.resolve())
  const [booting, setBooting] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [account, setAccount] = useState<BilibiliAccountStatus>(loggedOut)
  const [accountChecking, setAccountChecking] = useState(true)
  const [accountError, setAccountError] = useState<string | null>(null)
  const [accountMenu, setAccountMenu] = useState(false)
  const accountController = useRef<AccountGateController | null>(null)
  const [status, setStatus] = useState<PlaybackStatus>(idle)
  const statusRef = useRef(status)
  const requestedSong = useRef<string | null>(null)
  const lastRecorded = useRef<string | null>(null)
  const playSequence = useRef(0)
  const playOperations = useRef<Promise<unknown>>(Promise.resolve())
  const [actionPending, setActionPending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [guest, setGuest] = useState(false)
  const [accountSessionKey, setAccountSessionKey] = useState(0)
  const [searchMode, setSearchMode] = useState<SearchMode>('song')
  const searchModeRef = useRef<SearchMode>('song')
  const queryRef = useRef('')
  const searchInputRef = useRef<HTMLInputElement>(null)
  const [query, setQuery] = useState('')
  const [searchedQuery, setSearchedQuery] = useState('')
  const [result, setResult] = useState<SearchResult | null>(null)
  const [searching, setSearching] = useState(false)
  const searchSequence = useRef(0)
  const [lyrics, setLyrics] = useState<LyricsLookupResult | null>(null)
  const [lyricsIdentity, setLyricsIdentity] = useState('')
  const [lyricsLoading, setLyricsLoading] = useState(false)
  const [lyricsError, setLyricsError] = useState<string | null>(null)
  const [lyricsRetry, setLyricsRetry] = useState(0)
  const [playerPane, setPlayerPane] = useState<'lyrics' | 'queue'>('lyrics')
  const [calibrating, setCalibrating] = useState(false)
  const [showTiming, setShowTiming] = useState(false)
  const lyricScroll = useRef<HTMLDivElement>(null)
  const lyricNodes = useRef(new Map<number, HTMLButtonElement>())
  const [dialog, setDialog] = useState<Dialog | null>(null)
  const [dialogName, setDialogName] = useState('')
  const [dialogBusy, setDialogBusy] = useState(false)
  const [folders, setFolders] = useState<BilibiliFavoriteFolder[]>([])
  const [folderMid, setFolderMid] = useState(0)
  const [folderLoading, setFolderLoading] = useState(false)
  const [folderError, setFolderError] = useState<string | null>(null)
  const [volumeDraft, setVolumeDraft] = useState(70)
  const [seekDraft, setSeekDraft] = useState<number | null>(null)
  const [sleepUntil, setSleepUntil] = useState<number | null>(null)
  const [backupBusy, setBackupBusy] = useState(false)
  const [restoreSettings, setRestoreSettings] = useState(false)
  const seekSequence = useRef(0)
  const nextRef = useRef<() => void>(() => {})
  const current = status.song
  const provider = preferences?.lyricsProvider ?? 'bilibili'
  const offset = current && preferences ? getLyricOffset(preferences, provider, current.bvid) : 0
  const syncedLines = useMemo(() => parseLrc(lyrics?.match?.syncedLyrics ?? ''), [lyrics])
  const activeLine = activeLyricIndex(syncedLines, status.currentTime, offset)
  const favorites = useMemo(() => new Set(library?.favorites.map(song => song.bvid)), [library?.favorites])
  const playlist = library?.playlists.find(item => item.id === libraryView)
  const playing = status.state === 'playing'
  const busy = status.state === 'loading' || actionPending

  const run = useCallback(async (action: () => Promise<unknown>) => {
    setError(null)
    try { await action() } catch (failure) { setError(message(failure)) }
  }, [])

  const mutateLibrary = useCallback((mutate: (state: LibraryState) => LibraryState): Promise<LibraryState> => {
    const operation = libraryWrites.current.catch(() => {}).then(async () => {
      if (!storesReady.current || !libraryRef.current) throw new Error('音乐库尚未完整读取，暂时无法保存，请重试读取或从备份恢复。')
      const next = mutate(libraryRef.current)
      const saved = await mobile.saveLibrary(next)
      libraryRef.current = saved
      setLibrary(saved)
      return saved
    })
    libraryWrites.current = operation
    return operation
  }, [])

  const mutatePreferences = useCallback((mutate: (state: MobilePreferences) => MobilePreferences): Promise<MobilePreferences> => {
    const operation = preferenceWrites.current.catch(() => {}).then(async () => {
      if (!storesReady.current || !preferencesRef.current) throw new Error('设置尚未完整读取，暂时无法保存，请重试读取或从备份恢复。')
      const next = mutate(preferencesRef.current)
      await mobile.savePreferences(next)
      preferencesRef.current = next
      setPreferences(next)
      return next
    })
    preferenceWrites.current = operation
    return operation
  }, [])

  const checkAccount = useCallback(() => accountController.current?.refresh() ?? Promise.resolve(), [])

  const loadStores = useCallback(async () => {
    storesReady.current = false
    setBooting(true)
    setLoadError(null)
    const loaded = await Promise.allSettled([mobile.loadLibrary(), mobile.loadPreferences()])
    const failures: string[] = []
    if (loaded[0].status === 'fulfilled') {
      libraryRef.current = loaded[0].value
      setLibrary(loaded[0].value)
      setVolumeDraft(Math.round(loaded[0].value.settings.volume * 100))
      if (isAndroid) void mobile.setVolume(loaded[0].value.settings.volume).catch(failure => setError(`恢复音量失败：${message(failure)}`))
    } else failures.push(message(loaded[0].reason))
    if (loaded[1].status === 'fulfilled') {
      preferencesRef.current = loaded[1].value
      setPreferences(loaded[1].value)
      searchModeRef.current = loaded[1].value.searchMode ?? 'song'
      setSearchMode(searchModeRef.current)
    } else failures.push(message(loaded[1].reason))
    if (failures.length) setLoadError(failures.join(' '))
    storesReady.current = failures.length === 0
    setBooting(false)
  }, [])

  const receiveStatus = useCallback((next: PlaybackStatus) => {
    if (requestedSong.current && next.song?.bvid !== requestedSong.current) return
    statusRef.current = next
    setStatus(next)
    if (next.error && next.state !== 'error') setError(next.error)
    if (!isAndroid && next.state === 'playing' && next.song && lastRecorded.current !== next.song.bvid && libraryRef.current) {
      lastRecorded.current = next.song.bvid
      void mutateLibrary(state => recordHistory(state, next.song!)).catch(failure => {
        lastRecorded.current = null
        setError(`播放已开始，但历史记录保存失败：${message(failure)}`)
      })
    }
    if (isAndroid && next.state === 'playing' && next.song && lastRecorded.current !== next.song.bvid) {
      lastRecorded.current = next.song.bvid
      const operation = libraryWrites.current.catch(() => {}).then(async () => { const value = await mobile.loadLibrary(); libraryRef.current = value; setLibrary(value) })
      libraryWrites.current = operation
      void operation.catch(failure => setError(message(failure)))
    }
  }, [mutateLibrary])

  useEffect(() => {
    let stopped = false
    const gate = new AccountGateController(() => mobile.checkAccount(), next => {
      if (stopped) return
      setAccount(next.status)
      setAccountChecking(next.checking)
      setAccountError(next.error)
    })
    accountController.current = gate
    void loadStores()
    void gate.refresh()
    const handles = [
      mobile.addStatusListener(next => { if (!stopped) receiveStatus(next) }),
      mobile.addEndedListener(song => {
        if (!isAndroid && !stopped && song.bvid === statusRef.current.song?.bvid && !requestedSong.current) nextRef.current()
      }),
      mobile.addStoreListener(key => {
        if (stopped) return
        if (key === 'sleep_until') void mobile.getSleepTimer().then(value => { if (!stopped) setSleepUntil(value.until) }).catch(failure => setError(message(failure)))
        if (key === 'background_history') {
          const operation = libraryWrites.current.catch(() => {}).then(async () => { const saved = await mobile.loadLibrary(); if (!stopped) { libraryRef.current = saved; setLibrary(saved) } })
          libraryWrites.current = operation
          void operation.catch(failure => setError(message(failure)))
        }
      }),
      mobile.addSessionListener(() => {
        if (stopped) return
        setAccountSessionKey(value => value + 1)
        searchSequence.current++
        setSearching(false)
        setResult(null)
        setLyricsRetry(value => value + 1)
        void checkAccount()
      }),
    ]
    void mobile.getStatus().then(next => { if (!stopped) receiveStatus(next) }).catch(failure => { if (!stopped) setError(message(failure)) })
    for (const pending of handles) void pending.catch(failure => { if (!stopped) setError(message(failure)) })
    const refresh = () => {
      if (document.visibilityState !== 'visible') return
      void mobile.getStatus().then(receiveStatus).catch(failure => setError(message(failure)))
      void mobile.getSleepTimer().then(value => setSleepUntil(value.until)).catch(failure => setError(message(failure)))
      if (isAndroid) void checkAccount()
    }
    document.addEventListener('visibilitychange', refresh)
    return () => {
      stopped = true
      gate.dispose()
      if (accountController.current === gate) accountController.current = null
      document.removeEventListener('visibilitychange', refresh)
      for (const pending of handles) void pending.then(handle => handle.remove()).catch(() => {})
    }
  }, [checkAccount, loadStores, receiveStatus])

  useEffect(() => {
    if (!notice) return
    const timer = window.setTimeout(() => setNotice(null), 4000)
    return () => clearTimeout(timer)
  }, [notice])

  useEffect(() => {
    setCalibrating(false)
    setSeekDraft(null)
    setLyrics(null)
    setLyricsIdentity('')
    setLyricsError(null)
    if (!current) { setLyricsLoading(false); return }
    let cancelled = false
    setLyricsLoading(true)
    void mobile.getLyrics(current, provider, lyricsRetry > 0).then(value => {
      if (!cancelled) { setLyrics(value); setLyricsIdentity(`${provider}:${current.bvid}`) }
    }).catch(failure => { if (!cancelled) setLyricsError(message(failure)) }).finally(() => { if (!cancelled) setLyricsLoading(false) })
    return () => { cancelled = true }
  }, [current?.bvid, provider, lyricsRetry])

  useEffect(() => {
    if (tab !== 'player' || playerPane !== 'lyrics' || calibrating) return
    const container = lyricScroll.current
    const node = lyricNodes.current.get(activeLine)
    if (!container || !node) return
    let stopped = false
    const centerLine = () => {
      if (stopped) return
      const top = container.scrollTop + node.getBoundingClientRect().top - container.getBoundingClientRect().top - (container.clientHeight - node.clientHeight) / 2
      container.scrollTo({ top, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' })
    }
    centerLine()
    const resize = new ResizeObserver(centerLine)
    resize.observe(container); resize.observe(node)
    void document.fonts.ready.then(centerLine)
    document.fonts.addEventListener('loadingdone', centerLine)
    return () => { stopped = true; resize.disconnect(); document.fonts.removeEventListener('loadingdone', centerLine) }
  }, [activeLine, tab, playerPane, calibrating, preferences?.lyricStyle])

  useEffect(() => {
    if (!dialog) return
    setDialogName(dialog.type === 'rename' ? dialog.name : '')
    const previous = document.activeElement as HTMLElement | null
    const timer = window.setTimeout(() => document.querySelector<HTMLElement>('.sheet input, .sheet button')?.focus(), 0)
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !dialogBusy) setDialog(null)
      if (event.key === 'Tab') {
        const nodes = [...document.querySelectorAll<HTMLElement>('.sheet button:not(:disabled), .sheet input')]
        const at = nodes.indexOf(document.activeElement as HTMLElement)
        if (event.shiftKey && at === 0) { event.preventDefault(); nodes[nodes.length - 1]?.focus() }
        else if (!event.shiftKey && at === nodes.length - 1) { event.preventDefault(); nodes[0]?.focus() }
      }
    }
    document.addEventListener('keydown', escape)
    return () => { clearTimeout(timer); document.removeEventListener('keydown', escape); previous?.focus() }
  }, [dialog, dialogBusy])

  const playSong = useCallback((song: Song, queue: Song[]) => {
    const sequence = ++playSequence.current
    requestedSong.current = song.bvid
    setActionPending(true)
    setError(null)
    const operation = playOperations.current.catch(() => {}).then(async () => {
      if (sequence !== playSequence.current) return
      await mutateLibrary(state => ({ ...state, queue: queue.length ? queue : [song] }))
      if (sequence !== playSequence.current) return
      await mobile.play(song)
      if (sequence === playSequence.current) receiveStatus(await mobile.getStatus())
    })
    playOperations.current = operation
    void operation.catch(failure => { if (sequence === playSequence.current) setError(message(failure)) }).finally(() => {
      if (sequence === playSequence.current) { requestedSong.current = null; setActionPending(false) }
    })
  }, [mutateLibrary, receiveStatus])

  const nextSong = useCallback((direction = 1) => {
    if (isAndroid) { void run(async () => receiveStatus(await mobile.skip(direction < 0 ? -1 : 1))); return }
    const saved = libraryRef.current
    if (!saved?.queue.length) return
    const index = saved.queue.findIndex(song => song.bvid === statusRef.current.song?.bvid)
    const next = getNextIndex(saved.queue, index, saved.settings.playMode, direction)
    if (next >= 0) playSong(saved.queue[next], saved.queue)
  }, [playSong, receiveStatus, run])
  nextRef.current = () => nextSong()

  const togglePlay = () => void run(async () => {
    if (playing) await mobile.pause()
    else if (current && status.state !== 'ended' && status.state !== 'error') await mobile.resume()
    else if (current) { playSong(current, library?.queue ?? [current]); return }
    else if (library?.queue.length) { playSong(library.queue[0], library.queue); return }
    receiveStatus(await mobile.getStatus())
  })

  const changeQuery = (value: string) => { queryRef.current = value; setQuery(value) }
  const searchSongs = async (text: string, page = 1, mode = searchModeRef.current) => {
    const clean = text.trim()
    if (!clean) { searchSequence.current++; setSearching(false); setResult(null); setSearchedQuery(''); return }
    const sequence = ++searchSequence.current
    setSearching(true); setError(null); setSearchedQuery(clean)
    if (page === 1) setResult(null)
    try {
      const found = await mobile.search(clean, page, mode)
      if (sequence !== searchSequence.current || mode !== searchModeRef.current) return
      setResult(previous => {
        if (page === 1 || !previous || previous.query !== found.query) return found
        const songs = [...new Map([...previous.songs, ...found.songs].map(song => [song.bvid, song])).values()]
        return { ...found, songs: mode === 'song' ? rankSongSearchResults(songs, clean) : songs }
      })
    } catch (failure) { if (sequence === searchSequence.current) setError(message(failure)) }
    finally { if (sequence === searchSequence.current) setSearching(false) }
  }
  const changeSearchMode = (mode: SearchMode) => {
    if (mode === searchModeRef.current) return
    searchModeRef.current = mode; setSearchMode(mode)
    void run(() => mutatePreferences(state => ({ ...state, searchMode: mode })))
    void searchSongs(queryRef.current, 1, mode)
  }
  useEffect(() => {
    const theme = preferences?.theme ?? 'classic'
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    let stopped = false
    const refresh = () => {
      applyTheme(theme, media.matches)
      if (isAndroid) void mobile.setAppearance(theme, media.matches).then(result => {
        if (!stopped) applyTheme(theme, result.dark)
      }).catch(failure => { if (!stopped) setError(message(failure)) })
    }
    refresh(); media.addEventListener('change', refresh)
    const listener = isAndroid ? mobile.addAppearanceListener(refresh) : null
    void listener?.catch(failure => { if (!stopped) setError(message(failure)) })
    return () => { stopped = true; media.removeEventListener('change', refresh); void listener?.then(handle => handle.remove()).catch(() => {}) }
  }, [preferences?.theme])

  const favoriteSong = (song: Song) => void run(async () => {
    const was = libraryRef.current?.favorites.some(item => item.bvid === song.bvid)
    await mutateLibrary(state => toggleFavorite(state, song))
    setNotice(was ? '已取消收藏' : '已保存到我的收藏')
  })
  const setSource = (source: LyricsProvider) => void run(() => mutatePreferences(state => ({ ...state, lyricsProvider: source })))
  const setOffset = async (value: number) => {
    if (!current) return
    const bvid = current.bvid
    await mutatePreferences(state => ({ ...state, lyricOffsets: { ...state.lyricOffsets, [`${provider}:${bvid}`]: Math.round(Math.max(-120, Math.min(120, value)) * 100) / 100 } }))
  }
  const changeOffset = (delta: number) => void run(async () => {
    if (!current) return
    const bvid = current.bvid
    await mutatePreferences(state => ({ ...state, lyricOffsets: { ...state.lyricOffsets, [`${provider}:${bvid}`]: Math.round(Math.max(-120, Math.min(120, getLyricOffset(state, provider, bvid) + delta)) * 100) / 100 } }))
  })
  const lyricClick = (lineTime: number) => {
    if (calibrating) {
      const calibrated = statusRef.current.currentTime - lineTime
      void run(async () => {
        await setOffset(calibrated)
        setCalibrating(false)
        setNotice('已对齐这一句，校准已为这首歌保存')
      })
    } else void run(async () => { await mobile.seek(Math.max(0, lineTime + offset)); receiveStatus(await mobile.getStatus()) })
  }
  const cycleMode = () => void run(() => mutateLibrary(state => {
    const next = state.settings.playMode === 'sequence' ? 'repeat' : state.settings.playMode === 'repeat' ? 'shuffle' : 'sequence'
    return { ...state, settings: { ...state.settings, playMode: next } }
  }))
  const saveVolume = () => void run(async () => {
    const volume = volumeDraft / 100
    await mutateLibrary(state => ({ ...state, settings: { ...state.settings, volume, ...(volume > 0 ? { volumeBeforeMute: volume } : {}) } }))
    await mobile.setVolume(volume)
    receiveStatus(await mobile.getStatus())
  })
  const mute = () => void run(async () => {
    const saved = libraryRef.current
    if (!saved) return
    const volume = saved.settings.volume > 0 ? 0 : saved.settings.volumeBeforeMute ?? .7
    await mutateLibrary(state => ({ ...state, settings: { ...state.settings, volume, volumeBeforeMute: state.settings.volume > 0 ? state.settings.volume : volume } }))
    setVolumeDraft(Math.round(volume * 100)); await mobile.setVolume(volume); receiveStatus(await mobile.getStatus())
  })
  const setSleep = (minutes: number) => void run(async () => {
    const value = await mobile.setSleepTimer(minutes ? Date.now() + minutes * 60000 : null)
    setSleepUntil(value.until); setNotice(value.until ? `${minutes} 分钟后暂停播放` : '已取消睡眠定时')
  })
  useEffect(() => { if (isAndroid) void mobile.getSleepTimer().then(value => setSleepUntil(value.until)).catch(failure => setError(message(failure))) }, [])
  useEffect(() => {
    if (!isAndroid || !current || lyricsLoading || lyricsIdentity !== `${provider}:${current.bvid}`) return
    void mobile.syncFloatingLyrics(current.bvid, provider, syncedLines, offset, lyrics?.match?.plainLyrics ?? '').catch(failure => setError(message(failure)))
  }, [current?.bvid, provider, lyricsIdentity, syncedLines, offset, lyricsLoading, lyrics?.match?.plainLyrics])
  const backup = (importing: boolean) => {
    setBackupBusy(true)
    void run(async () => {
      await Promise.all([libraryWrites.current, preferenceWrites.current])
      if (importing) {
        const { data } = await mobile.importBackup()
        if (data !== null) { setRestoreSettings(!libraryRef.current || !preferencesRef.current); setDialog({ type: 'backup', backup: parseBackup(data) }) }
      } else if (libraryRef.current && preferencesRef.current) {
        const result = await mobile.exportBackup(createBackup(libraryRef.current, preferencesRef.current, await mobile.getFloatingLyrics()))
        if (result.saved) setNotice('备份已保存到所选位置')
      }
    }).finally(() => setBackupBusy(false))
  }
  const restore = () => {
    if (dialog?.type !== 'backup') return
    setDialogBusy(true)
    const imported = dialog.backup
    const operation = libraryWrites.current.catch(() => {}).then(async () => {
      await preferenceWrites.current.catch(() => {})
      const prefs = restoreSettings ? imported.preferences ?? (preferencesRef.current ? undefined : defaultPreferences()) : undefined
      const next = await mobile.restoreBackup(libraryRef.current ? mergeLibraryBackup(libraryRef.current, imported.library, restoreSettings) : imported.library, prefs, restoreSettings ? imported.floatingLyrics : undefined)
      libraryRef.current = next; setLibrary(next); setVolumeDraft(Math.round(next.settings.volume * 100))
      if (prefs) { preferencesRef.current = prefs; setPreferences(prefs); searchModeRef.current = prefs.searchMode ?? 'song'; setSearchMode(searchModeRef.current) }
      if (preferencesRef.current) { storesReady.current = true; setLoadError(null) }
      await mobile.setVolume(next.settings.volume)
      setDialog(null); setNotice('已合并备份，现有收藏和歌单已保留')
    })
    libraryWrites.current = operation
    void run(() => operation).finally(() => setDialogBusy(false))
  }
  const commitSeek = (seconds: number) => {
    const sequence = ++seekSequence.current
    const bvid = statusRef.current.song?.bvid
    void run(async () => {
      await mobile.seek(seconds)
      const next = await mobile.getStatus()
      if (sequence === seekSequence.current && next.song?.bvid === bvid) receiveStatus(next)
    }).finally(() => { if (sequence === seekSequence.current) setSeekDraft(null) })
  }
  const openFolders = () => {
    setDialog({ type: 'folders' })
    setFolders([])
    setFolderError(null)
    setFolderLoading(true)
    void mobile.getFavoriteFolders().then(value => { setFolders(value.folders); setFolderMid(value.mid) })
      .catch(failure => setFolderError(message(failure))).finally(() => setFolderLoading(false))
  }
  const importFolder = async (folder: BilibiliFavoriteFolder) => {
    setDialogBusy(true)
    setFolderError(null)
    try {
      const imported = await mobile.getFavoriteSongs(folder.id)
      let playlistId = ''
      await mutateLibrary(state => {
        const added = importBilibiliPlaylist(state, { accountMid: folderMid, folderId: folder.id, name: folder.title, songs: imported.songs })
        playlistId = added.playlistId
        return added.state
      })
      setDialog(null)
      setLibraryView(playlistId)
      setTab('library')
      setNotice(`已导入 ${imported.songs.length} 首${imported.skippedCount ? `，${imported.skippedCount} 个失效视频已跳过` : ''}`)
    } catch (failure) { setFolderError(message(failure)) }
    finally { setDialogBusy(false) }
  }
  const submitDialog = async (event: FormEvent) => {
    event.preventDefault()
    if (!dialog || dialogBusy) return
    setDialogBusy(true)
    setError(null)
    try {
      if (dialog.type === 'create') {
        if (!dialogName.trim()) throw new Error('请填写歌单名称。')
        if ((libraryRef.current?.playlists.length ?? 0) >= 100) throw new Error('最多支持 100 份歌单，请先移除不需要的歌单。')
        const next = await mutateLibrary(state => createPlaylist(state, dialogName))
        setLibraryView(next.playlists[next.playlists.length - 1]?.id ?? 'overview')
        setTab('library')
      } else if (dialog.type === 'rename') await mutateLibrary(state => renamePlaylist(state, dialog.id, dialogName))
      else if (dialog.type === 'delete') {
        await mutateLibrary(state => deletePlaylist(state, dialog.id))
        setLibraryView('overview')
      }
      setDialog(null)
      setNotice('已保存')
    } catch (failure) { setError(message(failure)) }
    finally { setDialogBusy(false) }
  }
  const rows = (songs: Song[], playlistId?: string) => songs.map((song, index) => <SongRow key={song.bvid} song={song}
    favorite={favorites.has(song.bvid)} active={current?.bvid === song.bvid} index={tab !== 'search' ? index : undefined}
    onPlay={() => playSong(song, songs)} onFavorite={() => favoriteSong(song)} onMore={() => setDialog({ type: 'song', song, playlistId })} />)
  const login = () => void run(async () => { await mobile.login(); await checkAccount() })
  const canUse = !booting && !loadError && library !== null && preferences !== null
  useEffect(() => {
    const keys = (event: KeyboardEvent) => {
      if (!canUse || dialog || event.repeat) return
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') { event.preventDefault(); setTab('search'); window.setTimeout(() => searchInputRef.current?.focus(), 0); return }
      const target = event.target as HTMLElement | null
      if (target?.closest('input, textarea, select, button, [contenteditable="true"]')) return
      if (event.code === 'Space') { event.preventDefault(); togglePlay() }
      else if (event.altKey && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) { event.preventDefault(); nextSong(event.key === 'ArrowLeft' ? -1 : 1) }
    }
    document.addEventListener('keydown', keys)
    return () => document.removeEventListener('keydown', keys)
  })

  return <div className={`app ${current ? 'has-player' : ''} ${!isAndroid ? 'preview-mode' : ''} ${error || loadError ? 'has-alert' : ''}`}>
    <header className="topbar">
      <button className="brand" onClick={() => { setTab('search'); setAccountMenu(false) }} aria-label="open音乐首页"><span className="brand-icon"><AudioLines size={23} strokeWidth={2.3} /></span><span>open音乐<small>OPEN MUSIC</small></span></button>
      <div className="account-wrap">
        <button className="account-avatar" onClick={() => setAccountMenu(!accountMenu)} aria-label="账号菜单" aria-expanded={accountMenu}>
          <Avatar url={account.loggedIn ? account.account.avatar : undefined} label={account.loggedIn ? account.account.username : ''} />
        </button>
        {accountMenu && <><button className="menu-dismiss" onClick={() => setAccountMenu(false)} aria-label="关闭账号菜单" /><div className="account-menu">
          <strong>{account.loggedIn ? account.account.username : '尚未登录'}</strong><span>{account.loggedIn ? `Bilibili · UID ${account.account.mid}` : '登录你的 Bilibili 账号'}</span>
          <button onClick={() => { setAccountMenu(false); login() }}><LogIn size={17} />{account.loggedIn ? '切换 / 管理账号' : '登录 Bilibili'}</button>
          <button onClick={() => { setAccountMenu(false); void checkAccount() }}><RefreshCw size={17} />刷新登录状态</button>
          {account.loggedIn && <button onClick={() => { setAccountMenu(false); openFolders() }}><FolderHeart size={17} />导入我的收藏夹</button>}
        </div></>}
      </div>
    </header>

    {!isAndroid && <div className="preview-banner"><Info size={16} /><span>手机版界面预览 · 登录和音频播放请在 Android 安装版中使用</span></div>}
    {loadError && <div className="alert persistent" role="alert"><span>{loadError}</span><button onClick={() => void loadStores()}>重试读取</button><button disabled={backupBusy} onClick={() => backup(true)}>从备份恢复</button></div>}
    {error && <div className="alert" role="alert"><span>{error}</span><button className="icon-button" onClick={() => setError(null)} aria-label="关闭错误提示"><X size={18} /></button></div>}
    {notice && <div className="toast" role="status"><Check size={17} />{notice}</div>}
    {updates.showBanner && tab !== 'settings' && <UpdateCard update={updates} compact />}

    {booting ? <main className="loading-screen"><LoaderCircle className="spin" size={28} /><p>正在读取你的音乐库…</p></main> : isAndroid && !account.loggedIn && !guest ?
      <main className="login-screen">
        <div className="login-art" aria-hidden="true"><span className="orbit orbit-one" /><span className="orbit orbit-two" /><div className="record"><div className="record-grooves" /><div className="record-label"><AudioLines size={25} /><span>OPEN MUSIC</span></div></div></div>
        <span className="eyebrow">WELCOME TO OPEN MUSIC</span><h1>登录，开始听见喜欢</h1><p>使用你的 Bilibili 账号搜索音乐，<br />把收藏夹里的好声音带到这里。</p>
        <button className="primary-button wide" onClick={login}><LogIn size={19} />登录 Bilibili<ArrowRight size={19} /></button>
        <button className="text-button" onClick={() => void checkAccount()} disabled={accountChecking}>{accountChecking ? <LoaderCircle className="spin" size={16} /> : <RefreshCw size={16} />}我已登录，重新检查</button>
        <button className="text-button" onClick={() => setGuest(true)}>暂不登录，先逛逛</button>
        <button className="text-button" onClick={updates.check} disabled={updates.pending}>检查软件更新</button>
        {!updates.state.update && updates.state.state === 'error' && <p className="inline-error" role="alert">{updates.state.message}</p>}
        {accountError && <p className="inline-error" role="alert">{accountError}</p>}
        <span className="login-note">登录在 Bilibili 官方页面完成<br />你的账号与音乐库保存在这台手机上</span>
      </main> : <main className={`content ${tab === 'player' ? 'player-content' : ''} ${tab === 'player' && showTiming ? 'timing-visible' : ''}`}>
      {tab === 'search' && <>
        <div className="page-intro"><span className="eyebrow">YOUR EVERYDAY SOUNDTRACK</span><h1>发现音乐<span className="heading-dot" aria-hidden="true">.</span></h1><p>让熟悉的旋律，遇见今天的心情。</p></div>
        <SearchBox query={query} mode={searchMode} searching={searching} inputRef={searchInputRef} onQueryChange={changeQuery} onModeChange={changeSearchMode} onSearch={text => void searchSongs(text)} />
        {!result && !searching && !searchedQuery && <>
          <div className="discovery-card"><div><span className="pill"><Radio size={13} />BILIBILI 音乐</span><h2>把日子，听成<br />喜欢的样子。</h2><p>一首歌，一点留白。<br />搜索喜欢的声音，点击开始播放。</p></div><ThemeArtwork /><div className="hero-art" aria-hidden="true"><div className="hero-ring ring-one" /><div className="hero-ring ring-two" /><div className="record"><div className="record-grooves" /><div className="record-label"><AudioLines size={23} /><span>OPEN MUSIC</span></div></div></div></div>
          <div className="section-heading"><h2>从这里开始</h2><span>随心搜索</span></div>
          <div className="suggestions">{['周杰伦', '陈奕迅', '纯音乐', '爵士乐', '雨天', '深夜耳机'].map(text => <button key={text} onClick={() => { changeQuery(text); void searchSongs(text) }}>{text}<ChevronRight size={14} /></button>)}</div>
          <div className="quiet-note"><Heart size={16} /><p>喜欢的歌，点一下爱心收藏。<br />下一次打开，它们还在这里。</p></div>
        </>}
        {searching && <div className="empty-state compact"><LoaderCircle className="spin" size={26} /><p>正在搜索「{searchedQuery}」…</p></div>}
        {result && !searching && <>
          <div className="section-heading search-heading"><h2>搜索结果 <small>{result.total}</small></h2><span>{searchMode === 'song' ? '单曲优先' : 'Bilibili 综合排序'}</span></div>
          {result.songs.length ? <div className="song-list">{rows(result.songs)}</div> : <Empty icon={<Search size={29} />} title="没有找到相关视频" text="换一个歌名或加上歌手，再试一次。" />}
          {result.hasMore && <div className="pagination"><button onClick={() => void searchSongs(result.query, result.page + 1)} disabled={searching}>加载更多<ChevronRight size={18} /></button></div>}
        </>}
      </>}

      {tab === 'library' && <>
        {libraryView === 'overview' ? <>
          <div className="page-intro row-intro"><div><span className="eyebrow">YOUR COLLECTION</span><h1>我的音乐库</h1><p>把喜欢的声音，好好收起来。</p></div><button className="icon-button outlined" aria-label="新建歌单" onClick={() => setDialog({ type: 'create' })} disabled={!canUse}><Plus size={23} /></button></div>
          <div className="library-shortcuts"><button onClick={() => setLibraryView('favorites')}><span className="shortcut-icon heart"><Heart size={24} /></span><strong>我的收藏</strong><span>{library?.favorites.length ?? 0} 首歌曲</span><ChevronRight className="shortcut-arrow" size={18} /></button><button onClick={() => setLibraryView('history')}><span className="shortcut-icon"><Clock3 size={24} /></span><strong>最近播放</strong><span>{library?.history.length ?? 0} 首歌曲</span><ChevronRight className="shortcut-arrow" size={18} /></button></div>
          <button className="import-card" onClick={openFolders} disabled={!canUse}><FolderHeart size={26} /><span><strong>导入 Bilibili 收藏夹</strong><small>把账号收藏夹，变成你的歌单</small></span><ChevronRight size={19} /></button>
          <div className="section-heading"><h2>创建的歌单 <small>{library?.playlists.length ?? 0}</small></h2><button className="text-button" onClick={() => setDialog({ type: 'create' })} disabled={!canUse}><Plus size={16} />新建</button></div>
          <div className="playlist-grid">{library?.playlists.map(item => <button key={item.id} className="playlist-card" onClick={() => setLibraryView(item.id)}><span className={`playlist-art ${item.songs.length ? 'has-cover' : ''}`}>{item.songs.length ? <Cover song={item.songs[0]} /> : <><ListMusic size={34} /><span className="record-ring" /></>}<span className="playlist-play"><Play size={16} fill="currentColor" /></span></span><strong>{item.name}</strong><span>{item.songs.length} 首歌曲</span></button>)}</div>
          {!library?.playlists.length && <Empty icon={<ListMusic size={30} />} title="还没有歌单" text="为喜欢的声音，取一个名字吧。" />}
        </> : <>
          <button className="back-button" onClick={() => setLibraryView('overview')}><ChevronLeft size={19} />我的音乐库</button>
          <div className="collection-header"><span className="collection-art">{libraryView === 'favorites' ? <Heart size={35} /> : libraryView === 'history' ? <Clock3 size={35} /> : <ListMusic size={35} />}</span><span><span className="eyebrow">MY MUSIC</span><h1>{libraryView === 'favorites' ? '我的收藏' : libraryView === 'history' ? '最近播放' : playlist?.name ?? '歌单'}</h1><p>{libraryView === 'favorites' ? `${library?.favorites.length ?? 0} 首，都是心头好` : libraryView === 'history' ? '记录每一次认真听过的声音' : `${playlist?.songs.length ?? 0} 首歌曲`}</p></span></div>
          {playlist && <div className="playlist-tools"><button className="primary-button" disabled={!playlist.songs.length || !canUse} onClick={() => playSong(playlist.songs[0], playlist.songs)}><Play size={17} fill="currentColor" />播放全部</button><button className="icon-button" aria-label="重命名歌单" onClick={() => setDialog({ type: 'rename', id: playlist.id, name: playlist.name })}><Pencil size={19} /></button><button className="icon-button" aria-label="删除歌单" onClick={() => setDialog({ type: 'delete', id: playlist.id, name: playlist.name })}><Trash2 size={19} /></button></div>}
          <div className="song-list">{rows(libraryView === 'favorites' ? library?.favorites ?? [] : libraryView === 'history' ? library?.history.map(entry => entry.song) ?? [] : playlist?.songs ?? [], playlist?.id)}</div>
          {!(libraryView === 'favorites' ? library?.favorites.length : libraryView === 'history' ? library?.history.length : playlist?.songs.length) && <Empty icon={<Music2 size={30} />} title="这里还很安静" text={libraryView === 'history' ? '开始播放后，听过的歌会留在这里。' : '去搜索一首歌，收藏或加入歌单。'} action={<button className="text-button" onClick={() => setTab('search')}>去发现音乐<ArrowRight size={16} /></button>} />}
        </>}
      </>}

      {tab === 'player' && <>
        {!current ? <Empty icon={<AudioLines size={44} />} title="等一首喜欢的歌" text="搜索一首歌，点击结果开始播放。" action={<button className="primary-button" onClick={() => setTab('search')}><Search size={18} />去搜索</button>} /> : <>
          <div className="now-header"><span className="eyebrow">NOW PLAYING</span><button className="icon-button" aria-label="打开当前视频 MV" onClick={() => void run(() => mobile.openSource())}><MonitorPlay size={22} /></button><button className="icon-button" aria-label="打开当前视频评论区" onClick={() => setTab('comments')}><MessageCircle size={22} /></button><button className="icon-button" aria-label="当前歌曲更多操作" onClick={() => setDialog({ type: 'song', song: current })}><MoreHorizontal size={22} /></button></div>
          <div className="now-title"><span><h1>{current.title}</h1><p>{current.artist}</p></span><button className={`icon-button ${favorites.has(current.bvid) ? 'is-favorite' : ''}`} aria-label={favorites.has(current.bvid) ? '取消收藏当前歌曲' : '收藏当前歌曲'} aria-pressed={favorites.has(current.bvid)} onClick={() => favoriteSong(current)}><Heart size={24} fill={favorites.has(current.bvid) ? 'currentColor' : 'none'} /></button></div>
          <div className="player-pane-switch"><button className={playerPane === 'lyrics' ? 'selected' : ''} onClick={() => setPlayerPane('lyrics')}>歌词</button><button className={playerPane === 'queue' ? 'selected' : ''} onClick={() => setPlayerPane('queue')}>播放队列 <small>{library?.queue.length ?? 0}</small></button></div>
          {playerPane === 'lyrics' ? <>
            <div className="lyric-toolbar"><div className="source-select" role="group" aria-label="歌词来源"><button className={provider === 'bilibili' ? 'selected' : ''} onClick={() => setSource('bilibili')}>Bilibili 字幕</button><button className={provider === 'lrclib' ? 'selected' : ''} onClick={() => setSource('lrclib')}>搜索歌词</button></div><button className={`icon-button ${showTiming ? 'selected' : ''}`} aria-label="歌词同步设置" aria-expanded={showTiming} onClick={() => setShowTiming(!showTiming)}><SlidersHorizontal size={18} /></button></div>
            <label className="lyrics-style-select">歌词样式<select aria-label="播放页歌词样式" value={preferences?.lyricStyle ?? 'classic'} disabled={!canUse} onChange={event => { const lyricStyle = event.target.value as LyricStyle; void run(() => mutatePreferences(state => ({ ...state, lyricStyle }))) }}>{LYRIC_STYLE_OPTIONS.map(style => <option key={style.value} value={style.value}>{style.label}</option>)}</select></label>
            {showTiming && <div className="timing-panel"><div><span>歌词时间偏移</span><strong>{offset > 0 ? '+' : ''}{offset.toFixed(2)} 秒</strong></div><div className="timing-controls"><button onClick={() => changeOffset(-0.25)}>提前 0.25 秒</button><button onClick={() => void run(() => setOffset(provider === 'bilibili' ? -0.25 : 0))}>重置</button><button onClick={() => changeOffset(0.25)}>延后 0.25 秒</button></div><button className={`calibrate-button ${calibrating ? 'active' : ''}`} disabled={!syncedLines.length} onClick={() => setCalibrating(!calibrating)}>{calibrating ? '点击正在唱的那一句，完成对齐' : '听到哪一句，点一下校准'}</button><p>校准按歌曲和歌词来源分别保存。</p></div>}
            <div data-lyric-style={preferences?.lyricStyle ?? 'classic'} className={`lyric-window ${calibrating ? 'is-calibrating' : ''}`} ref={lyricScroll}>
              {lyricsLoading ? <div className="empty-state compact"><LoaderCircle className="spin" size={25} /><p>正在寻找歌词…</p></div> : lyricsError ? <Empty icon={<Music2 size={26} />} title="歌词暂时没有到达" text={lyricsError} action={<button className="text-button" onClick={() => setLyricsRetry(value => value + 1)}><RefreshCw size={16} />重试</button>} /> : syncedLines.length ? <div className="lyric-lines">{syncedLines.map((line, index) => <button key={`${line.time}:${index}`} ref={node => { if (node) lyricNodes.current.set(index, node); else lyricNodes.current.delete(index) }} className={`${activeLine === index ? 'active' : ''} ${!line.text ? 'instrumental-line' : ''}`} onClick={() => lyricClick(line.time)} aria-label={calibrating ? `对齐 ${line.text}` : `跳转到 ${time(line.time + offset)} ${line.text}`}>{line.text || '♪'}</button>)}</div> : lyrics?.match?.plainLyrics ? <div className="plain-lyrics"><span className="plain-label">该来源提供文本歌词，暂不支持逐句同步</span><p>{lyrics.match.plainLyrics}</p></div> : <Empty icon={<AudioLines size={35} />} title={lyrics?.match?.instrumental ? '纯音乐，静静听' : '这首歌还没有歌词'} text={lyrics?.message ?? '可以切换另一个歌词来源试试。'} />}
            </div>
            {lyrics?.subtitle && <p className="lyrics-credit">Bilibili · {lyrics.subtitle.label}{lyrics.subtitle.isAI ? ' · AI 识别字幕' : ''}</p>}
            {provider === 'lrclib' && lyrics?.match && <p className="lyrics-credit">LRCLIB · {lyrics.match.artistName} · {lyrics.match.trackName}</p>}
          </> : <div className="queue-list song-list"><div className="queue-heading"><span>{library?.queue.length ?? 0} 首歌曲</span><button className="text-button" disabled={!library?.queue.length} onClick={() => setDialog({ type: 'clearQueue' })}><Trash2 size={16} />清空队列</button></div>{(library?.queue ?? []).map((song, index) => <SongRow key={song.bvid} song={song} index={index} favorite={favorites.has(song.bvid)} active={current.bvid === song.bvid} onPlay={() => playSong(song, library?.queue ?? [])} onFavorite={() => favoriteSong(song)} onMore={() => setDialog({ type: 'song', song, queue: true })} />)}{!library?.queue.length && <p className="update-caption">队列已空，当前歌曲继续播放。可从歌曲菜单加入新歌曲。</p>}</div>}
          <div className="player-controls"><div className="seek-track"><input type="range" min={0} max={Math.max(1, status.duration || current.duration)} step={0.1} value={Math.min(seekDraft ?? status.currentTime, Math.max(1, status.duration || current.duration))} aria-label="播放进度" onChange={event => setSeekDraft(Number(event.target.value))} onPointerUp={event => commitSeek(Number(event.currentTarget.value))} onKeyUp={event => { if (event.key.startsWith('Arrow') || ['Home', 'End'].includes(event.key)) commitSeek(Number(event.currentTarget.value)) }} style={{ '--progress': `${status.currentTime / Math.max(1, status.duration || current.duration) * 100}%` } as React.CSSProperties} /><span>{time(status.currentTime)}</span><span>{time(status.duration || current.duration)}</span></div><div className="transport"><button className="icon-button" aria-label={modeLabel[library?.settings.playMode ?? 'sequence']} onClick={cycleMode}>{library?.settings.playMode === 'repeat' ? <Repeat1 size={22} /> : library?.settings.playMode === 'shuffle' ? <Shuffle size={22} /> : <Repeat size={22} />}</button><button className="icon-button skip-button" aria-label="上一首" disabled={!library?.queue.length || busy} onClick={() => nextSong(-1)}><SkipBack size={27} fill="currentColor" /></button><button className="big-play" aria-label={playing ? '暂停' : '播放'} disabled={busy} onClick={togglePlay}>{busy ? <LoaderCircle className="spin" size={29} /> : playing ? <Pause size={29} fill="currentColor" /> : <Play size={29} fill="currentColor" />}</button><button className="icon-button skip-button" aria-label="下一首" disabled={!library?.queue.length || busy} onClick={() => nextSong()}><SkipForward size={27} fill="currentColor" /></button><button className="icon-button" aria-label="加入歌单" onClick={() => setDialog({ type: 'add', song: current })}><ListMusic size={22} /></button></div><p className="playback-caption">{status.state === 'error' ? status.error || '播放失败，请重试' : busy ? '正在准备播放…' : playing ? '正在播放 · Bilibili' : status.state === 'ended' ? '播放结束' : '已暂停'}</p></div>
        </>}
      </>}

      {current && <div hidden={tab !== 'comments'}><CommentsView song={current} account={account.loggedIn ? account.account : null} accountChecking={accountChecking} sessionKey={accountSessionKey} commentsApi={mobile.comments} onLogin={login} onOpenSource={() => void run(() => mobile.openSource())} /></div>}
      {tab === 'comments' && !current && <Empty icon={<MessageCircle size={30} />} title="先选择一首歌" text="播放后可以查看原视频评论区。" />}
      {tab === 'settings' && <>
        <div className="page-intro"><span className="eyebrow">MAKE IT YOURS</span><h1>听歌设置</h1><p>习惯的方式，舒服的声音。</p></div>
        <UpdateCard update={updates} />
        <div className="settings-card account-card"><span className="settings-account-avatar"><Avatar url={account.loggedIn ? account.account.avatar : undefined} /></span><span><strong>{account.loggedIn ? account.account.username : 'Bilibili 账号'}</strong><small>{account.loggedIn ? `UID ${account.account.mid}` : '登录后使用搜索和播放'}</small></span><button className="text-button" onClick={login}>{account.loggedIn ? '管理' : '登录'}<ChevronRight size={16} /></button></div>
        <div className="settings-card"><div className="settings-label"><Volume2 size={19} /><span><strong>播放音量</strong><small>静音后可恢复之前的音量</small></span><button className="icon-button" disabled={!canUse} aria-label={library?.settings.volume === 0 ? '取消静音' : '静音'} onClick={mute}>{library?.settings.volume === 0 ? <VolumeX size={20} /> : <Volume2 size={20} />}</button><b>{volumeDraft}%</b></div><input type="range" min={0} max={100} value={volumeDraft} aria-label="播放音量" onChange={event => setVolumeDraft(Number(event.target.value))} onPointerUp={saveVolume} onKeyUp={event => { if (event.key.startsWith('Arrow') || ['Home', 'End'].includes(event.key)) saveVolume() }} /></div>
        <div className="settings-card"><div className="settings-label"><Timer size={19} /><span><strong>睡眠定时</strong><small>{sleepUntil && sleepUntil > Date.now() ? `将在 ${new Date(sleepUntil).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })} 暂停播放` : '到时暂停，熄屏后继续计时'}</small></span></div><label className="setting-select-label">暂停时间<select aria-label="睡眠定时" disabled={!isAndroid || !canUse} value={sleepUntil && sleepUntil > Date.now() ? 'active' : '0'} onChange={event => setSleep(Number(event.target.value))}><option value="0">关闭</option>{sleepUntil && sleepUntil > Date.now() && <option value="active">已设定</option>}<option value="15">15 分钟后</option><option value="30">30 分钟后</option><option value="60">60 分钟后</option><option value="90">90 分钟后</option></select></label></div>
        <FloatingLyricsSettings onError={setError} />
        <div className="settings-card"><div className="settings-label"><Download size={19} /><span><strong>音乐库备份</strong><small>JSON 备份包含收藏、歌单、历史、队列和设置</small></span></div><div className="setting-inline-actions"><button className="text-button" disabled={!isAndroid || !canUse || backupBusy} onClick={() => backup(false)}><Download size={17} />导出备份</button><button className="text-button" disabled={!isAndroid || !canUse || backupBusy} onClick={() => backup(true)}><Upload size={17} />导入并合并</button></div><p className="update-caption">支持 PC 歌库 JSON；导入前预览，合并去重。备份不包含 Bilibili 登录凭据。</p></div>
        <div className="settings-card"><div className="settings-label"><Music2 size={19} /><span><strong>默认歌词来源</strong><small>切换后，也会记住你的选择</small></span></div><div className="setting-options"><button className={provider === 'bilibili' ? 'selected' : ''} onClick={() => setSource('bilibili')}><span>Bilibili 字幕<small>与视频对应，优先使用</small></span>{provider === 'bilibili' && <Check size={19} />}</button><button className={provider === 'lrclib' ? 'selected' : ''} onClick={() => setSource('lrclib')}><span>搜索歌词<small>来自 LRCLIB，可逐首校准</small></span>{provider === 'lrclib' && <Check size={19} />}</button></div></div>
        <div className="settings-card"><div className="settings-label"><Repeat size={19} /><span><strong>播放模式</strong><small>{modeLabel[library?.settings.playMode ?? 'sequence']}</small></span><button className="text-button" onClick={cycleMode} disabled={!canUse}>切换<ChevronRight size={16} /></button></div></div>
        <div className="settings-card saved-note"><Check size={20} /><span><strong>你的音乐，会被记住</strong><p>收藏、歌单、播放历史、队列、音量和歌词校准都保存在这台手机上。手机版与 PC 版各自独立。</p></span></div>
        <ThemeSettings value={preferences?.theme ?? 'classic'} disabled={!canUse} onChange={theme => void run(() => mutatePreferences(state => ({ ...state, theme })))} />
        <section className="settings-card" aria-label="播放页歌词样式"><div className="settings-label"><AudioLines size={19} /><span><strong>歌词样式</strong><small>独立于界面主题，切换后自动保存</small></span></div><LyricStylePicker label="播放页歌词样式" value={preferences?.lyricStyle ?? 'classic'} disabled={!canUse} onChange={lyricStyle => void run(() => mutatePreferences(state => ({ ...state, lyricStyle })))} /></section>
        <div className="about"><span className="brand-icon"><AudioLines size={25} /></span><strong>open音乐 · 手机版</strong><span>版本 {packageInfo.version} · Android 预览版</span><p>给生活，留一点余音。</p></div>
      </>}
    </main>}

    {current && tab !== 'player' && !(isAndroid && !account.loggedIn && !guest) && <div className="mini-player"><button className="mini-song" onClick={() => setTab('player')} aria-label="打开正在播放与歌词"><Cover song={current} /><span><strong>{current.title}</strong><small>{current.artist}</small></span></button><button className="icon-button mini-play" onClick={togglePlay} disabled={busy} aria-label={playing ? '暂停' : '播放'}>{busy ? <LoaderCircle className="spin" size={23} /> : playing ? <Pause size={23} fill="currentColor" /> : <Play size={23} fill="currentColor" />}</button><button className="icon-button" onClick={() => { setTab('player'); setPlayerPane('queue') }} aria-label="打开播放队列"><ListMusic size={22} /></button><span className="mini-progress" style={{ width: `${Math.min(100, status.currentTime / Math.max(1, status.duration || current.duration) * 100)}%` }} /></div>}
    {!(isAndroid && !account.loggedIn && !guest) && <nav className="bottom-nav" aria-label="主导航">{([{ id: 'search', label: '发现', Icon: Search }, { id: 'library', label: '音乐库', Icon: Library }, { id: 'player', label: '正在听', Icon: AudioLines }, { id: 'settings', label: '设置', Icon: Settings2 }] as const).map(({ id, label, Icon }) => <button key={id} className={tab === id || tab === 'comments' && id === 'player' ? 'active' : ''} aria-current={tab === id || tab === 'comments' && id === 'player' ? 'page' : undefined} onClick={() => { setTab(id); setAccountMenu(false) }}><Icon size={22} /><span>{label}</span></button>)}</nav>}

    {dialog && <div className="sheet-backdrop" onClick={() => { if (!dialogBusy) setDialog(null) }}><section className="sheet" role="dialog" aria-modal="true" aria-label={dialog.type === 'create' ? '新建歌单' : dialog.type === 'rename' ? '重命名歌单' : dialog.type === 'delete' ? '删除歌单' : dialog.type === 'folders' ? 'Bilibili 收藏夹' : '歌曲操作'} onClick={event => event.stopPropagation()}><span className="sheet-handle" /><button className="icon-button sheet-close" aria-label="关闭" disabled={dialogBusy} onClick={() => setDialog(null)}><X size={21} /></button>
      {dialog.type === 'clearQueue' && <><h2>清空播放队列？</h2><p>当前歌曲继续播放，收藏、歌单和历史保留。</p><button className="primary-button wide danger" disabled={dialogBusy} onClick={() => { setDialogBusy(true); void run(async () => { await mutateLibrary(state => ({ ...state, queue: [] })); setDialog(null); setNotice('播放队列已清空') }).finally(() => setDialogBusy(false)) }}><Trash2 size={18} />确认清空</button></>}
      {dialog.type === 'backup' && <><h2>合并音乐库备份</h2><p>{dialog.backup.library.favorites.length} 首收藏 · {dialog.backup.library.playlists.length} 份歌单 · {dialog.backup.library.history.length} 条历史<br />{library ? '相同歌曲自动去重，现有歌单保留。' : '当前歌库不可读，将从备份恢复，并保留损坏的原记录。'}</p><label className="backup-choice"><input type="checkbox" checked={restoreSettings} disabled={dialogBusy || !preferences} onChange={event => setRestoreSettings(event.target.checked)} />同时恢复备份设置{!dialog.backup.preferences && '（仅音量与播放模式）'}</label><button className="primary-button wide" disabled={dialogBusy} onClick={restore}>{dialogBusy ? <LoaderCircle className="spin" size={18} /> : <Check size={18} />}确认合并</button></>}
      {(dialog.type === 'create' || dialog.type === 'rename' || dialog.type === 'delete') && <form onSubmit={event => void submitDialog(event)}><h2>{dialog.type === 'create' ? '新建歌单' : dialog.type === 'rename' ? '重命名歌单' : '删除这份歌单？'}</h2><p>{dialog.type === 'delete' ? `「${dialog.name}」将从本地歌单中移除。歌曲收藏和历史仍会保留。` : '给喜欢的声音，起一个好听的名字。'}</p>{dialog.type !== 'delete' && <input className="name-input" aria-label="歌单名称" placeholder="输入歌单名称" value={dialogName} onChange={event => setDialogName(event.target.value)} maxLength={80} required autoComplete="off" />}<button className={`primary-button wide ${dialog.type === 'delete' ? 'danger' : ''}`} disabled={dialogBusy || (dialog.type !== 'delete' && !dialogName.trim())}>{dialogBusy ? <LoaderCircle className="spin" size={18} /> : dialog.type === 'delete' ? <Trash2 size={18} /> : <Check size={18} />}{dialog.type === 'delete' ? '删除歌单' : '保存歌单'}</button></form>}
      {dialog.type === 'song' && <><div className="sheet-song"><Cover song={dialog.song} /><span><strong>{dialog.song.title}</strong><small>{dialog.song.artist}</small></span></div><button className="sheet-action" onClick={() => { favoriteSong(dialog.song); setDialog(null) }}><Heart size={20} fill={favorites.has(dialog.song.bvid) ? 'currentColor' : 'none'} />{favorites.has(dialog.song.bvid) ? '取消收藏' : '收藏这首歌'}</button><button className="sheet-action" onClick={() => setDialog({ type: 'add', song: dialog.song })}><Plus size={20} />加入歌单<ChevronRight size={17} /></button><button className="sheet-action" onClick={() => void run(async () => { await mutateLibrary(state => ({ ...state, queue: state.queue.some(song => song.bvid === dialog.song.bvid) ? state.queue : [...state.queue, dialog.song] })); setDialog(null); setNotice('已加入播放队列') })}><ListMusic size={20} />加入播放队列</button>{dialog.queue && <button className="sheet-action destructive" onClick={() => void run(async () => { await mutateLibrary(state => removeFromQueue(state, dialog.song.bvid)); setDialog(null); setNotice("已从队列移除，当前播放不受影响") })}><Trash2 size={20} />从队列移除</button>}{dialog.playlistId && <button className="sheet-action destructive" onClick={() => void run(async () => { await mutateLibrary(state => removeFromPlaylist(state, dialog.playlistId!, dialog.song.bvid)); setDialog(null); setNotice('已从歌单移除') })}><Trash2 size={20} />从此歌单移除</button>}<button className="sheet-action" onClick={() => void run(() => mobile.openSource())}><MonitorPlay size={20} />打开当前视频 MV</button></>}
      {dialog.type === 'add' && <><h2>加入歌单</h2><p className="sheet-track-name">{dialog.song.title}</p><div className="sheet-list">{library?.playlists.map(item => {
        const added = item.songs.some(song => song.bvid === dialog.song.bvid)
        return <button className="sheet-action" key={item.id} disabled={added || dialogBusy} onClick={() => { setDialogBusy(true); void run(async () => { await mutateLibrary(state => addToPlaylist(state, item.id, dialog.song)); setDialog(null); setNotice(`已加入「${item.name}」`) }).finally(() => setDialogBusy(false)) }}><ListMusic size={21} /><span>{item.name}<small>{item.songs.length} 首歌曲</small></span>{added ? <Check size={18} /> : <Plus size={18} />}</button>
      })}</div>{!library?.playlists.length && <p>先到音乐库新建一份歌单，再把这首歌加进来。</p>}</>}
      {dialog.type === 'folders' && <><h2>我的 Bilibili 收藏夹</h2><p>选择一份收藏夹，导入为独立的本地歌单。<br />再次导入会合并新歌曲。</p>{folderLoading ? <div className="empty-state compact"><LoaderCircle className="spin" size={24} /><p>正在读取收藏夹…</p></div> : folderError ? <div className="inline-error" role="alert">{folderError}<button className="text-button" onClick={openFolders}><RefreshCw size={16} />重新读取</button></div> : <div className="sheet-list">{folders.map(folder => <button className="sheet-action" key={folder.id} disabled={dialogBusy} onClick={() => void importFolder(folder)}><FolderHeart size={23} /><span>{folder.title}<small>{folder.mediaCount} 个视频</small></span>{dialogBusy ? <LoaderCircle className="spin" size={18} /> : <Plus size={18} />}</button>)}{!folders.length && <p>账号中暂时没有可导入的收藏夹。</p>}</div>}</>}
      {error && <p className="inline-error" role="alert">{error}</p>}
    </section></div>}
  </div>
}

function Empty({ icon, title, text, action }: { icon: React.ReactNode; title: string; text: string; action?: React.ReactNode }) {
  return <div className="empty-state"><span className="empty-icon">{icon}</span><h3>{title}</h3><p>{text}</p>{action}</div>
}
