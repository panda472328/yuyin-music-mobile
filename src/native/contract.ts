import type { PluginListenerHandle } from '@capacitor/core'
import type { Song, PlaybackStatus } from '../types'
import type { MobileUpdateState } from '../domain/updates'
export interface YuyinNativePlugin {
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
  writeStore(options: { key: 'library' | 'preferences'; data: string }): Promise<{ saved: boolean }>
  addListener(event: 'status', callback: (status: PlaybackStatus) => void): Promise<PluginListenerHandle>
  addListener(event: 'ended', callback: (event: { song: Song }) => void): Promise<PluginListenerHandle>
  addListener(event: 'sessionChanged', callback: () => void): Promise<PluginListenerHandle>
  addListener(event: 'updateState', callback: (state: MobileUpdateState) => void): Promise<PluginListenerHandle>
  addListener(event: 'appearanceChanged', callback: () => void): Promise<PluginListenerHandle>
}
export interface MobilePreferences {
  version: 1
  theme?: 'classic' | 'bilibili' | 'system' | 'pixel'
  searchMode?: 'song' | 'video'
  lyricsProvider: 'bilibili' | 'lrclib'
  lyricOffsets: Record<string, number>
}
