import type { PluginListenerHandle } from '@capacitor/core'
import type { Song, PlaybackStatus } from '../types'
import type { MobileUpdateState } from '../domain/updates'
export interface YuyinNativePlugin {
  skip(options: { direction: -1 | 1 }): Promise<PlaybackStatus>
  getSleepTimer(): Promise<{ until: number | null }>
  setSleepTimer(options: { until: number | null }): Promise<{ until: number | null }>
  getFloatingLyrics(): Promise<FloatingLyricsSettings>
  setFloatingLyrics(options: { settings: FloatingLyricsSettings }): Promise<FloatingLyricsSettings>
  requestOverlayPermission(): Promise<{ granted: boolean }>
  syncFloatingLyrics(options: { bvid: string; provider: 'bilibili' | 'lrclib'; lines: { time: number; text: string }[]; offset: number; fallback: string }): Promise<void>
  exportBackup(options: { data: string }): Promise<{ saved: boolean }>
  importBackup(): Promise<{ data: string | null }>
  restoreBackup(options: { library: string; preferences?: string; floatingLyrics?: FloatingLyricsSettings }): Promise<{ saved: boolean; data?: string }>
  request(options: { url: string; timeoutMs?: number }): Promise<{ status: number; body: string }>
  setAppearance(options: { theme: 'classic' | 'bilibili' | 'system' | 'pixel'; dark: boolean }): Promise<{ dark: boolean }>
  mutateComment(options: { action: 'like' | 'create'; bvid: string; expectedMid: number; id?: string; liked?: boolean; message?: string; rootId?: string; parentId?: string }): Promise<{ oid: string; data: unknown }>
  openLogin(): Promise<void>
  openSource(): Promise<void>
  play(options: { song: Song }): Promise<PlaybackStatus>
  pause(): Promise<PlaybackStatus>
  resume(): Promise<PlaybackStatus>
  seek(options: { seconds: number }): Promise<PlaybackStatus>
  setVolume(options: { volume: number }): Promise<PlaybackStatus>
  getStatus(): Promise<PlaybackStatus>
  checkUpdate(): Promise<MobileUpdateState>
  downloadUpdate(): Promise<MobileUpdateState>
  cancelUpdate(): Promise<MobileUpdateState>
  installUpdate(): Promise<MobileUpdateState>
  getUpdateState(): Promise<MobileUpdateState>
  readStore(options: { key: 'library' | 'preferences' }): Promise<{ data: string | null }>
  writeStore(options: { key: 'library' | 'preferences'; data: string }): Promise<{ saved: boolean; data?: string }>
  addListener(event: 'status', callback: (status: PlaybackStatus) => void): Promise<PluginListenerHandle>
  addListener(event: 'ended', callback: (event: { song: Song }) => void): Promise<PluginListenerHandle>
  addListener(event: 'sessionChanged', callback: () => void): Promise<PluginListenerHandle>
  addListener(event: 'updateState', callback: (state: MobileUpdateState) => void): Promise<PluginListenerHandle>
  addListener(event: 'appearanceChanged', callback: () => void): Promise<PluginListenerHandle>
  addListener(event: 'storeChanged', callback: (event: { key: string }) => void): Promise<PluginListenerHandle>
}
export interface FloatingLyricsSettings {
  enabled: boolean
  locked: boolean
  font: 'sans' | 'serif' | 'kai' | 'rounded'
  fontSize: number
  color: 'white' | 'mint' | 'gold'
  opacity: number
  x: number
  y: number
  permission: boolean
}
export interface MobilePreferences {
  version: 1
  theme?: 'classic' | 'bilibili' | 'system' | 'pixel'
  searchMode?: 'song' | 'video'
  lyricsProvider: 'bilibili' | 'lrclib'
  lyricOffsets: Record<string, number>
}
